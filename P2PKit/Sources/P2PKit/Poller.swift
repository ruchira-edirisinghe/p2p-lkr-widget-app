import Foundation

public protocol AdSource: Sendable {
    func fetch(side: Side, fiat: String, payment: PaymentMethod, rows: Int) async throws -> [Ad]
}

public protocol WidgetReloader: Sendable {
    func reload()
}

public protocol AlertPresenter: Sendable {
    func present(rule: AlertRule, price: Double) async
}

extension BinanceP2PClient: AdSource {
    public func fetch(side: Side, fiat: String,
                      payment: PaymentMethod, rows: Int) async throws -> [Ad] {
        try await search(side: side, fiat: fiat, payment: payment, rows: rows)
    }
}

public enum PollOutcome: Sendable {
    case stored(Sample)
    case failed(P2PError)
}

/// Owns one polling cycle: fetch, compute, persist, alert, reload.
public actor Poller {
    private let source: any AdSource
    private let store: Store
    private let settings: Settings
    private let reloader: any WidgetReloader
    private let presenter: any AlertPresenter
    private let retryDelay: TimeInterval

    public init(source: any AdSource, store: Store, settings: Settings,
                reloader: any WidgetReloader, presenter: any AlertPresenter,
                retryDelay: TimeInterval = 2) {
        self.source = source
        self.store = store
        self.settings = settings
        self.reloader = reloader
        self.presenter = presenter
        self.retryDelay = retryDelay
    }

    /// Spread requests around the interval instead of hitting the endpoint on
    /// a fixed beat.
    public static func nextDelay(interval: TimeInterval, jitter: TimeInterval) -> TimeInterval {
        interval + TimeInterval.random(in: -jitter...jitter)
    }

    public func pollOnce(now: Date = .now) async -> PollOutcome {
        let side = settings.side
        let amount = settings.amountUSDT

        let ads: [Ad]
        do {
            ads = try await fetchWithOneRetry(side: side)
        } catch let error as P2PError {
            return .failed(error)
        } catch {
            return .failed(.transport(error.localizedDescription))
        }

        guard let sample = MetricsEngine.makeSample(
            ads: ads, side: side, amountUSDT: amount, timestamp: now) else {
            return .failed(.emptyResult)
        }

        do {
            // A failed poll writes nothing at all: a gap in the chart is
            // honest, a fabricated price is not.
            try store.append(sample)
            try store.replaceSnapshot(side: side, ads: ads, capturedAt: now)
            try store.prune(olderThan: now.addingTimeInterval(
                -Double(Settings.Defaults.retentionDays) * 86_400))
        } catch {
            return .failed(.malformed(String(describing: error)))
        }

        if let price = sample.fillablePrice {
            await evaluateAlerts(side: side, amount: amount, price: price, now: now)
        }
        reloader.reload()
        return .stored(sample)
    }

    private func fetchWithOneRetry(side: Side) async throws -> [Ad] {
        do {
            return try await source.fetch(side: side, fiat: settings.fiat,
                                          payment: settings.payment, rows: 20)
        } catch {
            if retryDelay > 0 {
                try? await Task.sleep(for: .seconds(retryDelay))
            }
            return try await source.fetch(side: side, fiat: settings.fiat,
                                          payment: settings.payment, rows: 20)
        }
    }

    private func evaluateAlerts(side: Side, amount: Int, price: Double, now: Date) async {
        guard let rules = try? store.alertRules() else { return }
        for rule in rules where rule.side == side && rule.amountUSDT == amount {
            // `try?` on an optional-returning call yields a double optional;
            // flatten it before use. An unknown rule starts armed.
            let stored: AlertState?? = try? store.alertState(id: rule.id)
            let state = stored.flatMap { $0 } ?? .armed
            let decision = AlertEngine.evaluate(rule: rule, price: price, state: state)
            if decision.fire {
                await presenter.present(rule: rule, price: price)
            }
            try? store.upsertAlert(rule, state: decision.newState,
                                   firedAt: decision.fire ? now : nil)
        }
    }
}
