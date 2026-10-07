# USDT/LKR P2P Rate Widget

A native macOS desktop widget that tracks the Binance P2P USDT/LKR rate, shows
whether it is moving up or down, and charts history collected every 5 minutes.

Status: **working.** Collector and widget both shipped and verified running.

**Android:** an app with a resizable home-screen widget lives in
[`android/`](android/README.md). It is a separate Kotlin port of the same logic.
See the [design spec](docs/superpowers/specs/2026-09-07-p2p-lkr-widget-design.md)
and the [implementation plan](docs/superpowers/plans/2026-09-07-p2p-lkr-widget.md).

## Why

Binance P2P is where USDT/LKR price discovery actually happens, but the web page
shows only a live snapshot. You cannot tell whether today's 330.66 is a good
moment to sell or the bottom of a two-day slide.

## How it reads the rate

The P2P page is backed by a public, unauthenticated JSON endpoint, so no
scraping is involved:

```
POST https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search
```

The headline number is **not** the top row of the page. It is the best price from
an ad that can actually fill your configured trade size. At the time of writing
the top ad quoted 332.00 LKR but required a minimum order of 499,999 LKR
(~1,500 USDT); the real tradeable rate for 500 USDT was 331.00. Filtering for
fillability is the difference between a useful chart and one that spikes on ads
nobody can trade.

## Install

```sh
make install
```

That signs the build, verifies it, copies it with `ditto`, re-verifies the
installed copy, and launches it. Use nothing else to install.

`make build` is a compile check only — it passes `CODE_SIGNING_ALLOWED=NO`,
which yields an ad-hoc bundle with **no entitlements**. macOS silently refuses
to register an unsandboxed widget extension (`pkd: plug-ins must be sandboxed`),
so the widget never appears in the gallery and nothing tells you why.
`make verify` is the check that catches it.

The app has no Dock or menu bar icon by design. It registers itself as a login
item and collects a sample every five minutes while running. Re-launching it
from Spotlight opens the detail window. To add the widget: right-click the
desktop, choose Edit Widgets, search for "USDT/LKR Rate".

Confirm collection is working:

```sh
sqlite3 "$HOME/Library/Group Containers/UN798LFFKG.group.dev.dfanso.p2pmonitor/p2p.sqlite" \
  'SELECT ts, side, amount_usdt, fillable_price, top_price, adv_name
   FROM samples ORDER BY ts DESC LIMIT 3;'
```

`fillable_price` should be at or below `top_price`. On the first verified run it
read 330.70 against a top of book of 332.00 — a 1.30 LKR gap per unit that a
naive reading of the page would have got wrong.

## App icon

The icon is generated, not hand-drawn — `make icon` runs
`tools/make-icon.swift`, which renders all ten asset-catalog slots (seven
unique sizes) with Core Graphics. The PNGs are committed so a fresh clone
builds without running it.

A rising sparkline over an emerald ground, with the Sri Lankan rupee glyph
watermarked behind. It carries no Binance or Tether marks: those are
trademarks, and using them would misrepresent this as an official client.

At 16px a hairline stroke and the watermark both turn to mud, so the renderer
has three tiers — full detail at 64px and up, watermark dropped and stroke
thickened below that, and a simplified four-point line at 16px.

**The artwork is full bleed on purpose.** macOS 26 composites a legacy `.icns`
onto its own rounded container, so drawing our own squircle with a transparent
margin nested our shape inside Apple's and produced a small icon floating in a
dark plate. The renderer fills the canvas and lets the system apply the shape
and shadow; content stays inside an 80% safe area because the system rounds the
corners. To check what macOS actually resolves rather than what we wrote:

```sh
swift tools/resolve-icon.swift /Applications/P2PMonitor.app /tmp/icon.png
```

## Layout

| Path | Contents |
|---|---|
| `P2PKit/` | Shared Swift package: API client, models, store, metrics, alerts |
| `P2PMonitor/` | App target: 5-minute poller, settings, detail window |
| `P2PWidget/` | Widget extension (read-only) |
| `fixtures/` | Recorded API responses driving the offline test suite |
| `tools/` | `p2p.sh`, a standalone shell client for poking at the endpoint |
| `android/` | Android app and home-screen widget (Kotlin, Compose, Glance) |

## tools/p2p.sh

Queries the endpoint directly, no build required:

```sh
./tools/p2p.sh SELL 10   # ads for you to sell USDT into
./tools/p2p.sh BUY 10    # ads for you to buy USDT from
```

## Requirements

macOS 14+ (developed on 26.6), Xcode 26+, `xcodegen` (`brew install xcodegen`),
and a signing identity whose team can carry an App Group entitlement.

The Xcode project is generated, not committed. Run `make project` after cloning,
and again after adding or renaming any source file.
