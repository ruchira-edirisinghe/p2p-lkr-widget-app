# USDT/LKR P2P Rate Widget — Design

Date: 2026-09-07
Status: Approved

## Problem

Binance P2P is the practical price discovery venue for USDT/LKR. Checking the
rate means opening <https://p2p.binance.com/trade/sell/USDT?fiat=LKR&payment=BankSriLanka>
and reading the top row, which gives no history — you cannot tell whether 330.66
is a good moment to sell or the bottom of a two-day slide.

We want a native macOS desktop widget that shows the current best sell rate, the
direction it is moving, and a chart built from data collected on a schedule.

## Why a page scrape is not needed

The page is driven by a public, unauthenticated JSON endpoint:

```
POST https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search
Content-Type: application/json

{"fiat":"LKR","page":1,"rows":20,"tradeType":"SELL","asset":"USDT",
 "payTypes":["BankSriLanka"],"countries":[],"periods":[],
 "proMerchantAds":false,"shieldMerchantAds":false,"filterType":"all",
 "additionalKycVerifyFilter":0,"publisherType":null,
 "classifies":["mass","profession","fiat_trade"]}
```

The URL query parameters map directly onto the request body: `fiat=LKR` → `fiat`,
`payment=BankSriLanka` → `payTypes`, and the `/trade/sell/` path segment →
`tradeType: "SELL"`. No auth, cookies, or API key.

Request `tradeType` is *the user's* action; the returned `adv.tradeType` is the
*advertiser's* and is therefore inverted. Verified live: `tradeType: "SELL"`
returns ads around 330.95 and `"BUY"` returns ads around 331.50 — sell low, buy
high, the correct spread direction. One code path serves both sides.

Each ad supplies `adv.price`, `adv.tradableQuantity`, `adv.minSingleTransAmount`,
`adv.dynamicMaxSingleTransAmount`, `adv.payTimeLimit`, `adv.tradeMethods[]`, and
`advertiser.nickName` / `monthOrderCount` / `monthFinishRate` / `positiveRate`.

## Decisions

| Question | Decision |
|---|---|
| Form factor | Native WidgetKit desktop widget, small/medium/large |
| Headline metric | Best ad that can actually fill a configured trade size |
| Cadence / retention | Poll every 5 minutes, retain 30 days |
| Collector | The container app owns polling (approach B below) |
| Widget configuration | Per-widget via AppIntents: side, amount, window, payment |
| Notifications | Threshold crossing alerts |
| Click-through | Opens a detail window with full ad list and larger chart |

### Assumptions carried forward

These were flagged at design time and not separately answered; they are recorded
here as the chosen defaults rather than left open.

- **App visibility.** The app runs as a hidden login item — no Dock icon, no menu
  bar item. Because a fully invisible app with no affordance is a usability trap,
  re-launching it from Spotlight or Applications opens the detail window instead
  of silently doing nothing. Adding a menu bar item later is a small change.
- **Default trade amount.** Settings seed with **500 USDT**. Changeable globally
  in settings and overridable per widget instance.

## Approach

Three options were considered.

**A — LaunchAgent plus a short-lived collector binary.** `launchd` runs a Swift
CLI every 5 minutes; it polls, writes, and exits. Robust — survives app quit,
consumes no memory between polls, restarts after reboot. But a bare CLI cannot
cleanly post user notifications, and it adds a plist install step.

**B — The container app owns polling. Chosen.** One signed app bundle registered
as a login item via `SMAppService`, started hidden, running the 5-minute timer.
It writes SQLite, posts notifications, reloads widget timelines, and hosts the
detail and settings windows. The widget extension only ever reads. Two targets,
no plist, and every requested feature works natively. The cost is that quitting
the app stops collection; mitigated by launch-at-login and by the widget
rendering a visible stale state.

**C — The widget fetches its own data.** Rejected. Timeline reloads are
budget-capped at roughly 40–70 per day and the OS chooses when they occur, so
this yields neither a 5-minute cadence nor any history accumulated while the
widget is off-screen.

## Architecture

```
┌─ P2PMonitor.app  (login item, LSUIElement, no Dock icon) ─┐
│  Poller: 5-minute timer, ±20s jitter                      │
│  → BinanceP2PClient.search(side, fiat, payment)           │
│  → MetricsEngine.fillable(ads, amount)                    │
│  → Store.append(sample) + Store.replaceSnapshot(ads)      │
│  → AlertEngine.evaluate() → UNUserNotificationCenter      │
│  → WidgetCenter.reloadAllTimelines()                      │
│  Settings window · Detail window (chart + full ad table)  │
└────────────────────────┬──────────────────────────────────┘
                         │ App Group container
              UN798LFFKG.group.dev.dfanso.p2pmonitor
                         │  p2p.sqlite (WAL)
┌────────────────────────▼──────────────────────────────────┐
│ P2PWidget.appex — READ ONLY                               │
│  TimelineProvider reads latest sample + series window     │
│  AppIntent config: side · amount · window · payment       │
│  Small / Medium / Large, SwiftUI + Swift Charts           │
└───────────────────────────────────────────────────────────┘
```

**Approach B has one hard requirement that is easy to miss.** SwiftUI
terminates an app when its last window closes, and this app is an
`LSUIElement` agent with no windows by design. So `AppDelegate` must return
`false` from `applicationShouldTerminateAfterLastWindowClosed`. Without it the
process exits seconds after launch, never reaches the five-minute timer, and
writes only the single immediate poll from `start()` — then launchd respawns
it and the cycle repeats. The failure is genuinely deceptive: samples keep
appearing in the store, so collection looks healthy, while every row comes
from a different short-lived process and the cadence does not exist. Diagnose
it by checking whether the PID in the log changes between polls.

Signing uses the existing Developer ID Application certificate, team
`UN798LFFKG`. Because the app is distributed outside the Mac App Store, the App
Group identifier must carry the team prefix — `UN798LFFKG.group.dev.dfanso.p2pmonitor`,
not the bare `group.` form used by App Store apps.

WAL mode matters here: the app writes while the widget extension reads from a
separate process, and WAL lets those proceed without the reader blocking.

## Modules

Shared logic lives in a local Swift package, `P2PKit`, so it is testable without
launching the app or the widget.

| Module | Responsibility | Depends on |
|---|---|---|
| `BinanceP2PClient` | Owns the entire wire format: request body, headers, decoding, error classification | Foundation |
| `Models` | `Ad`, `Advertiser`, `Sample`, `Side`, `Window`, `PaymentMethod` | — |
| `MetricsEngine` | Pure functions: fillable selection, trend, downsampling | `Models` |
| `Store` | SQLite open/migrate, append, query series, snapshot, prune | `Models` |
| `AlertEngine` | Threshold state machine, produces notification requests | `Models`, `Store` |
| `Poller` (app) | Timer, jitter, retry, orchestration, widget reload | all of the above |

Every wire-format detail is confined to `BinanceP2PClient` so that a Binance
schema change is a one-file fix.

## The metric

An ad qualifies for a trade of `amount` USDT when **both** hold:

1. `tradableQuantity >= amount` — the advertiser has the stock, and
2. `minSingleTransAmount <= amount × price <= dynamicMaxSingleTransAmount` — the
   fiat value sits inside the advertiser's per-order limits.

Binance returns ads best-first on both sides, so **the first qualifying ad is the
answer** for sell and for buy alike. No separate maximise/minimise branch.

Worked example from `fixtures/lkr-sell-20260907.json` at 500 USDT
(≈ 165,500 LKR), which is the regression case for this whole filter:

| Price | Stock | LKR limits | Verdict |
|---|---|---|---|
| 332.00 | 1510 | 499,999 – 500,000 | rejected: 165,500 below the 499,999 minimum |
| 331.00 | 100 | 5,000 – 15,000 | rejected: stock 100 < 500, and above the max |
| 331.00 | 700 | 10,000 – 220,000 | **accepted → headline 331.00** |

Reporting 332.00 here would be actively misleading: that ad cannot be traded
without moving roughly 1,500 USDT in a single order.

Each sample also records raw top-of-book and the median of the top ten, so the
headline metric can be changed later without re-collecting history.

## Data model

```sql
samples(ts, side, amount_usdt, fillable_price, top_price, median_top10,
        adv_name, adv_available, adv_min_lkr, adv_max_lkr,
        PRIMARY KEY (ts, side, amount_usdt))

snapshot(side, captured_at, payload_json)   -- latest full ad list, detail window

alerts(id, side, amount_usdt, threshold, direction, last_state, last_fired_at)
```

`ts` is Unix seconds. `fillable_price` is nullable — a poll that found no
qualifying ad is a real, recordable observation and is distinct from a failed
poll, which writes no row at all.

Trend is the current fillable price against the sample nearest `now − window`.
Windows longer than 24h are bucket-averaged in SQL: 7d to hourly means, 30d to
6-hour means. A daily job prunes `ts < now − 30 days`.

At 288 samples per day per (side, amount) pair, 30 days is roughly 8,600 rows —
about 1 MB.

## Failure handling

A failed or malformed poll **writes no row**, leaving a gap rather than a
fabricated price. The chart draws gaps as breaks in the line, so overnight sleep
gaps and network failures both read honestly as missing data instead of as a flat
rate. One retry with backoff, then the tick is skipped.

If the newest sample is older than 15 minutes the widget renders a dimmed stale
state showing the age, so a stale number can never be misread as live.

**Known risk.** This endpoint is internal and undocumented. If Binance changes
the schema or introduces bot protection, the collector breaks. The mitigation is
containment, not prevention: the wire format lives in one file, parse failures
dump the raw response to a log for diagnosis, and the widget surfaces a distinct
feed-error state rather than silently freezing on an old value.

Polling is jittered ±20 seconds around the 5-minute mark to avoid hammering the
endpoint on a fixed beat. 288 requests per day is well inside any plausible
rate limit.

## Widget layouts

- **Small** — pair label, price, arrow with percentage, minimal sparkline.
- **Medium** — the above plus the chart with window low/high, best advertiser
  name and available stock, and a relative last-updated stamp.
- **Large** — the above plus a table of the top five ads: price, available,
  limits, advertiser.

Each size is configured independently through the same AppIntent: side, trade
amount, chart window, payment method.

## Testing

Recorded fixtures drive the tests offline, so the suite never touches the
network and stays deterministic as the live market moves.

- `fixtures/lkr-sell-20260907.json`, `fixtures/lkr-buy-20260907.json` — 20 real
  ads per side, captured 2026-09-07, including the 332.00 / min-499,999 ad.
- **MetricsEngine** — the worked example above, asserting 331.00 rather than
  332.00; empty-result and no-qualifying-ad cases; buy side picks the lowest
  qualifying price using the same first-match rule.
- **Store** — append and query round-trip, retention pruning at the boundary,
  concurrent read during write under WAL.
- **Trend and downsampling** — hand-computed series, including series containing
  gaps, verifying gaps are preserved rather than interpolated.
- **AlertEngine** — fires exactly once per crossing, stays quiet while the rate
  remains beyond the threshold, and re-arms only after crossing back.
- **BinanceP2PClient** — decodes the fixtures; classifies a non-`000000` code, an
  empty `data` array, and malformed JSON as distinct errors.

## Project layout

```
p2p-lkr-widget/
  P2PMonitor.xcodeproj
  P2PKit/                  Swift package: client, models, store, metrics, alerts
  P2PMonitor/              app target: poller, settings, detail window
  P2PWidget/               widget extension: timeline provider, SwiftUI views
  Tests/P2PKitTests/
  fixtures/
  docs/superpowers/specs/
```

## Out of scope

Assets other than USDT, fiats other than LKR, payment methods beyond those in
Binance's `payTypes` list, order placement or any authenticated Binance action,
and iOS or watchOS targets.
