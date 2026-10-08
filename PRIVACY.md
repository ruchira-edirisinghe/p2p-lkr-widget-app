# Privacy Policy — LKR P2P Rate

Last updated: 2026-10-08

LKR P2P Rate shows the USDT/LKR rate from the public Binance P2P order book.

**What the app collects about you: nothing.** It has no accounts, no analytics,
no advertising, no crash reporting and no tracking of any kind.

**Network requests.** The app sends one anonymous request per side (sell and buy)
to Binance's public P2P search endpoint
(`https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search`) about every 15
to 60 minutes, plus whenever you open the app or tap refresh. The request
contains only the currency pair, the payment method and the page size. No
identifier, location, or personal information is sent. Binance receives your IP
address as part of any internet request; see Binance's own privacy policy for how
they handle it.

For the Currencies tab and the Exchange Rate widget, the app downloads daily
reference-rate files from the open-source currency-api
(`https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api`, with
`currency-api.pages.dev` as a fallback) at most every few hours, plus older daily
files once when you first view a longer chart. These are plain file downloads:
nothing about you or the currencies you look at is sent. jsDelivr and Cloudflare
receive your IP address as part of the request.

**Data stored on your phone.** P2P rate history (30 days), downloaded exchange
rates, your favourite pairs, your settings and your alert thresholds are stored only in the app's private storage on your device. They are
never uploaded. Uninstalling the app deletes them.

**Notifications.** If you create a rate alert, the app asks for permission to show
notifications. Alerts are evaluated on your device.

**Open source.** The app's full source code is public, under the MIT License, at
<https://github.com/ruchira-edirisinghe/p2p-lkr-widget-app>, so all of the above can be
checked.

**Children.** The app is not directed at children.

The app is not affiliated with, endorsed by, or sponsored by Binance.

Contact: <add your support email here before publishing>
