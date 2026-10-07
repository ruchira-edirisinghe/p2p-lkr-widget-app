import Foundation
import P2PKit
import OSLog

/// Resolves the shared container once and holds the long-lived objects.
@MainActor
final class AppEnvironment {
    static let shared = AppEnvironment()

    let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "app")
    let settings = Settings.shared
    let store: Store?
    let collector: CollectorService?

    private init() {
        guard let url = AppGroup.databaseURL else {
            // Without the entitlement there is no shared store and the widget
            // could never read anything, so fail loudly rather than silently
            // collecting into a private container.
            logger.error("App Group container unavailable — check entitlements")
            store = nil
            collector = nil
            return
        }
        do {
            let store = try Store(fileURL: url)
            self.store = store
            self.collector = CollectorService(
                store: store,
                settings: settings,
                presenter: NotificationPresenter())
        } catch {
            logger.error("Store open failed: \(String(describing: error), privacy: .public)")
            store = nil
            collector = nil
        }
    }
}
