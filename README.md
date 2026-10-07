# LKR P2P Rate — Android app and home-screen widget

See the USDT/LKR P2P rate on your Android home screen. The app reads the
public Binance P2P order book and shows the best rate you can actually get,
with a trend chart, a converter, the live list of ads and rate alerts.

- **Language:** Kotlin, with Jetpack Compose for the app and Jetpack Glance for the widget
- **Runs on:** Android 8.0 (API 26) and newer, phones and tablets
- **Package:** `dev.dfanso.lkrp2p` (debug builds: `dev.dfanso.lkrp2p.debug`)

## Features

### Home-screen widget

One widget, resizable in both directions from a 2x1 strip to full screen. It
uses `SizeMode.Exact`, so every instance knows its real size and picks a layout:

| Size | Layout |
|---|---|
| Short (under ~110dp tall) | Rate + trend chip, sparkline on the right if wide enough |
| Medium | Title, big rate, "per 1 USDT" caption, chart (axis labels when tall enough) |
| Tall (250dp+) | The full card: ₮ 1 / ↓↑ / Rs rate, Day-Week-Month control, chart with dashed gridlines |

Type scales with the widget's width, numerals shrink before they reach the
currency badge, and corners follow the launcher's own widget radius.

Each widget remembers its own choices:
- **Day / Week / Month** switches the chart window.
- **↓↑** (or the title on smaller sizes) flips between the Sell and Buy rate.
- **⟳** fetches now. Tapping anywhere else opens the app.

### The app

Three tabs, built so the everyday question — "what's the rate right now?" —
needs no taps:

- **Rate**: a Sell/Buy switch showing both live rates; a USDT ⇄ LKR converter
  (starts at 1 USDT, quick amounts, ↓↑ swaps direction and keeps the numbers);
  a chart you can press or drag to read any point; low/high/average, buy–sell
  spread and market median; when the rate was last updated. Pull down to refresh.
- **Ads**: the live order book for any order size, with the best usable ad
  marked, ads that can't take your order folded away with the reason, and a
  button to open Binance P2P.
- **Alerts**: "tell me when the sell rate rises to X", pre-filled from the live
  rate, with a clear Watching / Reached / Paused status for each alert.
- **Settings** (gear on the Rate tab): order size, payment method, how often to
  check, which side new widgets show, and an Add widget button.

## How the rate is worked out

Everything shows the price of **1 USDT**. Which ad that price comes from depends
on the **order size** in Settings (default 500 USDT): the rate is the best ad
that will accept an order that big. P2P ads have LKR minimums, and the top ad
often needs far more than most people trade, so quoting it would be misleading.
Set the order size to roughly what you trade; each size keeps its own history.

- **Sell** = you give USDT and receive LKR (best = highest price).
- **Buy** = you pay LKR and receive USDT (best = lowest price).
- Binance pins "Promoted Ad" rows above the book regardless of price, so ads
  are sorted best-first before the fillable one is picked.

## Background updates

WorkManager checks both sides every **15 minutes** (30 or 60 in Settings) with
**no notification**. 15 minutes is the shortest interval Android allows for
background work; anything faster needs a foreground service with a permanent
notification, costs battery and runs into Play's foreground-service rules.
Doze and battery saver can stretch the interval while the phone is idle.

On top of that the app refreshes when you open it (if the rate is over a minute
old), when you pull down, and when you tap ⟳ on a widget. Both sides are
collected each time so a widget can flip without a gap in its history. If the
newest rate is over 45 minutes old the widget says **Stale** in amber.

History is kept on the phone for 30 days in SQLite and never leaves it.

## Project layout

| Path | Contents |
|---|---|
| `app/src/main/java/dev/dfanso/lkrp2p/core/` | Wire format, API client, fillable/median metrics, trend, converter, edge-triggered alerts |
| `.../data/` | SQLite store, settings, the poller |
| `.../work/` | WorkManager collector and alert notifications |
| `.../render/` | Background and chart painters shared by the widget and the app |
| `.../widget/` | Jetpack Glance widget with three size-dependent layouts |
| `.../ui/` | Compose app: Rate, Ads and Alerts tabs plus Settings |
| `app/src/test/` | Unit tests against recorded Binance responses, and render tests |
| `play/` | Store icon (512px), feature graphic (1024x500) and its generator |
| `PRIVACY.md` | Privacy policy text to host for the Play listing |

## Build

Requirements: JDK 17+ and the Android SDK (platform 36). Android Studio bundles
both — open this folder in it and press Run. From a terminal:

```sh
./gradlew testDebugUnitTest   # unit tests + screenshot renders
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug           # Android lint
```

`testDebugUnitTest` also renders the real widget at six home-screen sizes
(2x1 strip to tall 4x5) with Robolectric into `app/build/widget-shots/`, and the
four app screens into `app/build/app-shots/`, so layout changes can be checked
without a phone. The render tests need an x64 JDK.

`local.properties` (git-ignored) must point at the SDK, for example
`sdk.dir=C\:/Users/you/AppData/Local/Android/Sdk`.

### Install on your own phone

1. On the phone: Settings → About phone → tap **Build number** 7 times →
   back to Settings → Developer options → enable **USB debugging**.
2. Connect by USB and run `./gradlew installDebug`, **or** copy `app-debug.apk`
   to the phone and open it (allow "install unknown apps" for your file manager).
3. Open **LKR P2P Rate** once, then long-press the home screen → Widgets →
   **USDT/LKR Rate**, or use **Add widget** in the app's Settings.
4. If your phone has aggressive battery management (Xiaomi, Oppo, Vivo, Huawei,
   Samsung "sleeping apps"), set the app's battery usage to **Unrestricted**,
   otherwise background updates may stop.

The debug build installs side by side with the Play version.

## Release build and Google Play

### 1. Create an upload key (once — back it up; losing it is painful)

```sh
keytool -genkeypair -v -keystore upload-key.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` next to it (git-ignored, as is the `.jks`):

```properties
storeFile=upload-key.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

### 2. Build the bundle

```sh
./gradlew bundleRelease   # app/build/outputs/bundle/release/app-release.aab
```

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload.
The **application ID `dev.dfanso.lkrp2p` is permanent** once published — change
it now if you want a different one.

### 3. Play Console

1. Create a developer account at <https://play.google.com/console> (one-time
   US$25; identity verification takes a few days).
2. **Create app** → name "LKR P2P Rate", App, Free.
3. Upload `app-release.aab` and enrol in **Play App Signing** (default). Google
   holds the real signing key; yours is only the upload key.
4. **Store listing**: icon `play/icon-512.png`, feature graphic
   `play/feature-graphic.png`, at least 2 phone screenshots (the app and the home
   screen with the widget), short and full description (suggested text below).
5. **App content**:
   - Privacy policy: host `PRIVACY.md` somewhere public (GitHub Pages or a public
     gist works) and paste the URL. Fill in the contact email first.
   - Data safety: *No data collected, no data shared.*
   - Ads: No. Target audience: 18+. Content rating questionnaire: utility, no
     objectionable content.
   - Financial features declaration: the app displays rates only; it does not
     trade, hold funds, or offer financial services.
6. **Testing requirement for new personal accounts:** Google requires a closed
   test with **at least 12 testers opted in for 14 continuous days** before you
   can apply for production access. Organisation accounts are exempt. This is
   the longest step.
7. Promote to production and submit for review.

Things that get rate apps rejected, and how this one avoids them: no Binance or
Tether logos or names in the icon or title (the name is "LKR P2P Rate"; the
listing may say *uses public Binance P2P data* and *not affiliated with
Binance*); no claims of being an official or trading app; no "real-time" claims.

### Suggested listing text

**Short description (80 chars):**
Live USDT/LKR P2P rate widget with trend chart and rate alerts.

**Full description:**
See the real USDT/LKR P2P rate right on your home screen.

LKR P2P Rate reads the public P2P order book and shows the best rate you can
actually get for your order size — not the top advert that needs a minimum you
can't meet.

• Resizable home-screen widget, from a slim strip to a full card
• Day, week and month trend chart, right on the widget
• Switch between selling and buying with one tap
• USDT ⇄ LKR converter priced from real adverts
• Full order book with the best usable advert highlighted
• Alerts when the rate rises above or falls below your target
• History stays on your phone. No account, no ads, no tracking.

Uses public Binance P2P data. Not affiliated with Binance. Not financial advice.

## Credits

The data model and rate logic are ported from the macOS widget at
[DFanso/p2p-lkr-widget](https://github.com/DFanso/p2p-lkr-widget).
