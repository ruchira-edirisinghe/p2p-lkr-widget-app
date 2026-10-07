import WidgetKit
import Foundation
import P2PKit

struct RateEntry: TimelineEntry {
    enum State: Equatable {
        case ok
        /// Newest sample is older than the staleness threshold.
        case stale(since: Date)
        case noData
        case error
    }

    let date: Date
    let side: Side
    let amountUSDT: Int
    let window: ChartWindow
    let sample: Sample?
    let series: [SeriesPoint]
    let trend: Trend?
    let state: State
    /// Top ads for the large family.
    let topAds: [Ad]

    static func placeholder(date: Date = .now) -> RateEntry {
        RateEntry(date: date, side: .sell, amountUSDT: 500, window: .hour24,
                  sample: nil, series: [], trend: nil, state: .noData, topAds: [])
    }
}
