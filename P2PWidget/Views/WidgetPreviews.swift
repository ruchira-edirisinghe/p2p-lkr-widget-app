import SwiftUI
import WidgetKit
import P2PKit

private func previewSample(_ price: Double?, at date: Date) -> Sample {
    Sample(timestamp: date, side: .sell, amountUSDT: 500, fillablePrice: price,
           topPrice: 332.00, medianTop10: 330.765, advertiserName: "TD_TrustPay_LK",
           advertiserAvailable: 700, advertiserMinFiat: 10_000, advertiserMaxFiat: 220_000)
}

private func previewSeries(rising: Bool) -> [SeriesPoint] {
    (0..<48).map { index in
        let drift = rising ? Double(index) * 0.02 : -Double(index) * 0.02
        let wobble = sin(Double(index) / 4) * 0.15
        return SeriesPoint(timestamp: Date(timeIntervalSince1970: 1_000 + Double(index) * 300),
                           price: 330.6 + drift + wobble)
    }
}

private func previewAds() -> [Ad] {
    [
        (332.00, 1510.0, 499_999.0, 500_000.0, "jeewani 98"),
        (331.00, 700.0, 10_000.0, 220_000.0, "TD_TrustPay_LK"),
        (330.95, 660.1, 4_000.0, 50_000.0, "Supuni_Tharanga"),
        (330.85, 22.65, 1_000.0, 7_000.0, "SHPK_Crypto"),
        (330.68, 1700.0, 300_000.0, 562_156.0, "DIGIT_FAST_BLACKROCK"),
    ].map { price, stock, low, high, who in
        Ad(price: price, availableUSDT: stock, minFiat: low, maxFiat: high,
           payTimeLimitMinutes: 15, advertiserName: who, monthOrderCount: 300,
           monthFinishRate: 0.99, positiveRate: 1.0)
    }
}

private func previewEntry(state: RateEntry.State = .ok, rising: Bool = true) -> RateEntry {
    let now = Date(timeIntervalSince1970: 1_000 + 48 * 300)
    let series = previewSeries(rising: rising)
    return RateEntry(date: now, side: .sell, amountUSDT: 500, window: .hour24,
                     sample: previewSample(331.00, at: now), series: series,
                     trend: MetricsEngine.trend(series: series), state: state,
                     topAds: previewAds())
}

#Preview("Small · rising", as: .systemSmall) {
    RateWidget()
} timeline: {
    previewEntry()
}

#Preview("Medium · falling", as: .systemMedium) {
    RateWidget()
} timeline: {
    previewEntry(rising: false)
}

#Preview("Medium · stale", as: .systemMedium) {
    RateWidget()
} timeline: {
    previewEntry(state: .stale(since: Date(timeIntervalSince1970: 1_000)))
}

#Preview("Large · full book", as: .systemLarge) {
    RateWidget()
} timeline: {
    previewEntry()
}

#Preview("Small · no data", as: .systemSmall) {
    RateWidget()
} timeline: {
    RateEntry.placeholder()
}
