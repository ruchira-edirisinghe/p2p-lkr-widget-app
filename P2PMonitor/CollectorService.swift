import Foundation
import P2PKit
import OSLog

/// Drives `Poller` on a jittered five-minute cadence for as long as the app runs.
@MainActor
final class CollectorService {
    private let poller: Poller
    private let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "collector")
    private var task: Task<Void, Never>?

    private(set) var lastOutcomeDescription: String = "not started"

    init(store: Store, settings: Settings, presenter: any AlertPresenter) {
        poller = Poller(source: BinanceP2PClient(), store: store, settings: settings,
                        reloader: WidgetCenterReloader(), presenter: presenter)
    }

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in
            // Poll immediately so a fresh launch is not blank for five minutes.
            await self?.pollNow()
            while !Task.isCancelled {
                let delay = Poller.nextDelay(interval: Settings.Defaults.pollInterval,
                                             jitter: Settings.Defaults.jitter)
                try? await Task.sleep(for: .seconds(delay))
                if Task.isCancelled { break }
                await self?.pollNow()
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    func pollNow() async {
        let outcome = await poller.pollOnce()
        switch outcome {
        case .stored(let sample):
            let price = sample.fillablePrice.map { "\($0)" } ?? "none fillable"
            lastOutcomeDescription = "ok — \(price)"
            logger.info("poll stored \(price, privacy: .public)")
        case .failed(let error):
            lastOutcomeDescription = "failed — \(error)"
            // Deliberately no row written; the chart will show a gap.
            logger.warning("poll failed \(String(describing: error), privacy: .public)")
        }
    }
}
