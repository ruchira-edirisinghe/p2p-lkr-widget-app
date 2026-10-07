import Testing
import Foundation
@testable import P2PKit

private actor FakeSource: AdSource {
    var results: [Result<[Ad], any Error>]
    private(set) var callCount = 0
    init(results: [Result<[Ad], any Error>]) { self.results = results }
    func fetch(side: Side, fiat: String, payment: PaymentMethod, rows: Int) async throws -> [Ad] {
        callCount += 1
        guard !results.isEmpty else { throw P2PError.emptyResult }
        return try results.removeFirst().get()
    }
    func calls() -> Int { callCount }
}

private final class SpyReloader: WidgetReloader, @unchecked Sendable {
    var count = 0
    func reload() { count += 1 }
}

private actor SpyPresenter: AlertPresenter {
    private(set) var presented: [(String, Double)] = []
    func present(rule: AlertRule, price: Double) async { presented.append((rule.id, price)) }
    func all() -> [(String, Double)] { presented }
}

private func pollerFixtures() throws -> (Store, URL, Settings) {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-poller-\(UUID().uuidString).sqlite")
    let suite = "p2p-poller-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return (try Store(fileURL: url), url, Settings(defaults: defaults))
}

@Test func successfulPollStoresSampleSnapshotAndReloadsWidget() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let reloader = SpyReloader()

    let poller = Poller(source: FakeSource(results: [.success(ads)]), store: store,
                        settings: settings, reloader: reloader, presenter: SpyPresenter())
    let outcome = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))

    guard case .stored(let sample) = outcome else {
        Issue.record("expected .stored, got \(outcome)"); return
    }
    #expect(sample.fillablePrice == 331.00)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
    #expect(try store.snapshot(side: .sell)?.ads.count == 20)
    #expect(reloader.count == 1)
}

@Test func failedPollStoresNothingAndLeavesAGap() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let reloader = SpyReloader()

    let source = FakeSource(results: [
        .failure(P2PError.httpStatus(503)),
        .failure(P2PError.httpStatus(503)),
    ])
    let poller = Poller(source: source, store: store, settings: settings,
                        reloader: reloader, presenter: SpyPresenter(), retryDelay: 0)
    let outcome = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))

    guard case .failed = outcome else { Issue.record("expected .failed"); return }
    // A fabricated price would be worse than a hole in the chart.
    #expect(try store.sampleCount() == 0)
    #expect(reloader.count == 0)
}

@Test func retriesOnceBeforeGivingUp() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))

    let source = FakeSource(results: [.failure(P2PError.httpStatus(500)), .success(ads)])
    let poller = Poller(source: source, store: store, settings: settings,
                        reloader: SpyReloader(), presenter: SpyPresenter(), retryDelay: 0)
    let outcome = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))

    guard case .stored = outcome else { Issue.record("expected .stored"); return }
    #expect(await source.calls() == 2)
}

@Test func firesAlertOnceWhenThresholdIsCrossed() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))

    let rule = AlertRule(id: "r1", side: .sell, amountUSDT: 500,
                         threshold: 330.0, direction: .above)
    try store.upsertAlert(rule, state: .armed, firedAt: nil)

    let presenter = SpyPresenter()
    let poller = Poller(source: FakeSource(results: [.success(ads), .success(ads)]),
                        store: store, settings: settings,
                        reloader: SpyReloader(), presenter: presenter)

    _ = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_000))
    _ = await poller.pollOnce(now: Date(timeIntervalSince1970: 1_300))

    // 331.00 clears the 330.0 threshold on both polls, but only the first
    // transition notifies.
    #expect(await presenter.all().count == 1)
    #expect(try store.alertState(id: "r1") == .triggered)
}

@Test func prunesRowsBeyondTheRetentionWindow() async throws {
    let (store, url, settings) = try pollerFixtures()
    defer { try? FileManager.default.removeItem(at: url) }
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))

    let now = Date(timeIntervalSince1970: 40 * 86_400)
    try store.append(Sample(timestamp: Date(timeIntervalSince1970: 0), side: .sell,
                            amountUSDT: 500, fillablePrice: 300.0, topPrice: 300.0,
                            medianTop10: nil, advertiserName: nil, advertiserAvailable: nil,
                            advertiserMinFiat: nil, advertiserMaxFiat: nil))

    let poller = Poller(source: FakeSource(results: [.success(ads)]), store: store,
                        settings: settings, reloader: SpyReloader(), presenter: SpyPresenter())
    _ = await poller.pollOnce(now: now)

    // The 30-day-old row goes; the one just written stays.
    #expect(try store.sampleCount() == 1)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
}

@Test func jitterStaysWithinTheConfiguredBound() {
    let base = Settings.Defaults.pollInterval
    let jitter = Settings.Defaults.jitter
    for _ in 0..<200 {
        let delay = Poller.nextDelay(interval: base, jitter: jitter)
        #expect(delay >= base - jitter)
        #expect(delay <= base + jitter)
    }
}
