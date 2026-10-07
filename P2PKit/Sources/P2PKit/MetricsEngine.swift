import Foundation

public enum MetricsEngine {

    /// The best ad that can actually absorb `amountUSDT`.
    ///
    /// Binance returns ads best-first on both sides — sell descending, buy
    /// ascending (verified across the recorded fixtures) — so the first
    /// qualifying ad is optimal for either side and no maximise/minimise
    /// branch is needed.
    public static func fillable(_ ads: [Ad], amountUSDT: Int) -> Ad? {
        ads.first { $0.canFill(amountUSDT: amountUSDT) }
    }

    /// Raw top of the book, stored alongside the fillable price so the
    /// headline metric can change later without re-collecting history.
    public static func topPrice(_ ads: [Ad]) -> Double? {
        ads.first?.price
    }

    public static func medianTop10(_ ads: [Ad]) -> Double? {
        let prices = ads.prefix(10).map(\.price).sorted()
        guard !prices.isEmpty else { return nil }
        let mid = prices.count / 2
        return prices.count.isMultiple(of: 2)
            ? (prices[mid - 1] + prices[mid]) / 2
            : prices[mid]
    }

    /// Nil only when the book is empty — that is a failed observation. A book
    /// with no ad big enough yields a sample whose `fillablePrice` is nil,
    /// which is a real finding and must be stored.
    public static func makeSample(ads: [Ad], side: Side, amountUSDT: Int,
                                  timestamp: Date) -> Sample? {
        guard let top = topPrice(ads) else { return nil }
        let best = fillable(ads, amountUSDT: amountUSDT)
        return Sample(
            timestamp: timestamp,
            side: side,
            amountUSDT: amountUSDT,
            fillablePrice: best?.price,
            topPrice: top,
            medianTop10: medianTop10(ads),
            advertiserName: best?.advertiserName,
            advertiserAvailable: best?.availableUSDT,
            advertiserMinFiat: best?.minFiat,
            advertiserMaxFiat: best?.maxFiat
        )
    }

    /// Direction across the supplied window. The caller has already scoped the
    /// series to the window, so this compares its ends.
    public static func trend(series: [SeriesPoint]) -> Trend? {
        guard let first = series.first, let last = series.last, series.count >= 2
        else { return nil }
        return Trend(previous: first.price, current: last.price)
    }

    /// Bucket-average a series. `bucketSeconds == 0` returns it unchanged.
    ///
    /// Empty buckets are omitted rather than filled: a gap must stay a gap so
    /// the chart draws a break instead of implying a flat rate overnight.
    public static func downsample(_ series: [SeriesPoint], bucketSeconds: Int) -> [SeriesPoint] {
        guard bucketSeconds > 0 else { return series }
        let bucket = TimeInterval(bucketSeconds)

        var order: [TimeInterval] = []
        var sums: [TimeInterval: (total: Double, count: Int)] = [:]

        for point in series {
            let key = (point.timestamp.timeIntervalSince1970 / bucket).rounded(.down) * bucket
            if sums[key] == nil {
                order.append(key)
                sums[key] = (0, 0)
            }
            sums[key]!.total += point.price
            sums[key]!.count += 1
        }

        return order.map { key in
            let entry = sums[key]!
            return SeriesPoint(timestamp: Date(timeIntervalSince1970: key),
                               price: entry.total / Double(entry.count))
        }
    }
}
