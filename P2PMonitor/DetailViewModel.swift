import Foundation
import Observation
import P2PKit

@MainActor
@Observable
final class DetailViewModel {
    var window: ChartWindow {
        didSet { reload() }
    }
    private(set) var series: [SeriesPoint] = []
    private(set) var trend: Trend?
    private(set) var latest: Sample?
    private(set) var ads: [Ad] = []
    private(set) var capturedAt: Date?
    private(set) var isRefreshing = false

    private let store: Store?
    private let settings: Settings
    private let collector: CollectorService?

    init(store: Store?, settings: Settings, collector: CollectorService?) {
        self.store = store
        self.settings = settings
        self.collector = collector
        self.window = settings.window
        reload()
    }

    var amountUSDT: Int { settings.amountUSDT }

    func reload() {
        guard let store else { return }
        let side = settings.side
        let amount = settings.amountUSDT
        latest = try? store.latest(side: side, amountUSDT: amount)
        series = (try? store.series(side: side, amountUSDT: amount, window: window)) ?? []
        trend = MetricsEngine.trend(series: series)
        // Flatten the double optional that `try?` produces over an
        // optional-returning throwing call.
        let snapshot = (try? store.snapshot(side: side)).flatMap { $0 }
        ads = snapshot?.ads ?? []
        capturedAt = snapshot?.capturedAt
    }

    func refreshNow() async {
        isRefreshing = true
        await collector?.pollNow()
        isRefreshing = false
        reload()
    }
}
