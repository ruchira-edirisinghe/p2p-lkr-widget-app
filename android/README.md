# LKR P2P Rate — Android app and home-screen widget

The Android port of the macOS widget in this repo. Same data source, same
"fillable price" rule, same alert logic, plus a resizable home-screen widget
styled after the dark emerald currency card design.

## What's in it

| Path | Contents |
|---|---|
| `app/src/main/java/.../core/` | Port of P2PKit: wire format, API client, fillable/median metrics, trend, edge-triggered alerts |
| `.../data/` | SQLite store (same schema as macOS), settings, the poller |
| `.../work/` | WorkManager collector (every 15/30/60 min) and alert notifications |
| `.../render/` | Background + chart painters shared by widget and app |
| `.../widget/` | Jetpack Glance widget with three size-dependent layouts |
| `.../ui/` | Compose app: Rate, Ads and Alerts tabs plus Settings |
| `app/src/test/` | Unit tests against the same recorded Binance fixtures as the Swift suite |
| `play/` | Store icon (512px), feature graphic (1024x500) and its generator |
| `PRIVACY.md` | Privacy policy text to host for the Play listing |

### The widget

One widget, resizable in both directions from a 2x1 strip to full screen. It uses
`SizeMode.Exact`, so every instance knows its real size and picks a layout:

| Size | Layout |
|---|---|
| Short (under ~110dp tall) | Rate + trend chip, sparkline on the right if wide enough |
| Medium | Title, big rate, caption, chart (axis labels when tall enough) |
| Tall (250dp+) | The full reference card: ₮ 1 / ↓↑ / Rs rate, Day-Week-Month control, chart with dashed gridlines |

Type sizes scale with the widget's width, numerals shrink before they hit the
currency badge, and text is sized in dp so it fits the space it was measured for.

Per-widget controls, each widget remembering its own choice:
- **Day / Week / Month** switches the chart window.
- **↓↑** (or the title on smaller sizes) flips that widget between Sell and Buy.
- **⟳** fetches now. Tapping anywhere else opens the app.

### Rate per 1 USDT, priced from your order size

Everything shows the price of **1 USDT**. Which ad that price comes from depends
on the **order size** in Settings (default 500 USDT): the rate is the best ad
that will actually accept an order that big, because P2P ads have LKR minimums
and a headline ad you can't use is misleading. Set the order size to roughly what
you trade; each size keeps its own history.

### The app

Three tabs, built so the common question ("what's the rate right now?") needs no taps:

- **Rate**: Sell/Buy switch showing both live rates, a converter (USDT ⇄ LKR,
  defaults to 1 USDT, quick amounts, ↓↑ swaps direction and keeps the numbers),
  a chart you can press or drag to read any point, low/high/average, spread and
  market median, and when the rate was last updated. Pull down to refresh.
- **Ads**: the live order book for any order size, the best usable ad marked,
  ads that can't take your order folded away with the reason, and a button to
  open Binance P2P.
- **Alerts**: "tell me when the sell rate rises to X", pre-filled from the live
  rate, with a clear Watching / Reached / Paused status for each.
- **Settings** (gear on the Rate tab): order size, payment method, how often to
  check, which side new widgets show, and an Add widget button.

### Background updates — the honest version

The macOS collector polls every 5 minutes. **Android does not allow that for a
normal app**: WorkManager's minimum periodic interval is 15 minutes, and Doze /
battery saver can stretch it further while the phone sits idle. A foreground
service could poll faster but would show a permanent notification, drain the
battery, and run into Play's foreground-service restrictions — not worth it for a
rate widget. So: background updates every 15 min (configurable to 30/60)
with no notification, plus a refresh whenever you open the app (if the rate is
over a minute old), pull down, or tap ⟳. Both sides are collected
every cycle so any widget can flip without a gap in history. If the newest sample
is over 45 minutes old the widget says **Stale** in amber.

## Build

Requirements: JDK 17+ and the Android SDK (platform 36). Android Studio bundles
both — open the `android/` folder in it and press Run. From a terminal:

```sh
cd android
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

`testDebugUnitTest` also renders the real widget at six home-screen sizes
(2x1 strip to tall 4x5) with Robolectric and writes the PNGs to
`app/build/widget-shots/`, and the four app screens to `app/build/app-shots/`,
so layout changes can be checked without a phone.

`local.properties` (git-ignored) must point at the SDK, e.g.
`sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`.

### Put it on your own phone right now

1. On the phone: Settings → About phone → tap **Build number** 7 times →
   back to Settings → Developer options → enable **USB debugging**.
2. Connect by USB and run `./gradlew installDebug`, **or** copy `app-debug.apk`
   to the phone and open it (allow "install unknown apps" for your file manager).
3. Open **LKR P2P Rate** once, then long-press the home screen → Widgets →
   **USDT/LKR Rate**, or use the in-app **Add widget to home screen** button.
4. If your phone has aggressive battery management (Xiaomi, Oppo, Vivo, Huawei,
   Samsung "sleeping apps"), set the app's battery usage to **Unrestricted**,
   otherwise background updates may stop.

The debug build installs as `dev.dfanso.lkrp2p.debug`, side by side with the
Play version.

## Release build and Google Play

### 1. Create an upload key (once — back it up; losing it is painful)

```sh
keytool -genkeypair -v -keystore android/upload-key.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then create `android/keystore.properties` (git-ignored, as is the `.jks`):

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
   US$25, identity verification takes a few days).
2. **Create app** → name "LKR P2P Rate", App, Free.
3. Upload `app-release.aab` and enrol in **Play App Signing** (default). Google
   holds the real signing key; yours is only the upload key.
4. **Store listing**: icon `play/icon-512.png`, feature graphic
   `play/feature-graphic.png`, at least 2 phone screenshots (take them on your
   phone: app screen + home screen with the widget), short and full description
   (suggested text below).
5. **App content**:
   - Privacy policy: host `PRIVACY.md` somewhere public (a GitHub Pages page or a
     public gist works) and paste the URL. Fill in the contact email first.
   - Data safety: *No data collected, no data shared.*
   - Ads: No. Target audience: 18+. Content rating questionnaire: utility, no
     objectionable content.
   - Financial features declaration: the app displays rates only; it does not
     trade, hold funds, or offer financial services.
6. **Testing requirement for new personal accounts:** Google requires a closed
   test with **at least 12 testers opted in for 14 continuous days** before you
   can apply for production access. Organisation accounts are exempt. Plan for
   this — it is the longest step.
7. Promote to production and submit for review.

Things that get rate apps rejected, and how this one avoids them: no Binance or
Tether logos or names in the icon/title (the name is "LKR P2P Rate"; the listing
may say *uses public Binance P2P data* and *not affiliated with Binance*); no
claims of being an official or trading app; no misleading "real-time" claims.

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
• Full order book with the fillable advert highlighted
• Alerts when the rate rises above or falls below your target
• History stays on your phone. No account, no ads, no tracking.

Uses public Binance P2P data. Not affiliated with Binance. Not financial advice.
