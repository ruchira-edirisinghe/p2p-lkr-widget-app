import SwiftUI
import ServiceManagement
import P2PKit
import OSLog

@main
struct P2PMonitorApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        Window("USDT/LKR Rate", id: "detail") {
            DetailWindow(model: DetailViewModel(
                store: AppEnvironment.shared.store,
                settings: AppEnvironment.shared.settings,
                collector: AppEnvironment.shared.collector))
        }
        .defaultSize(width: 760, height: 600)
        // Qualified: SwiftUI.Settings collides with P2PKit.Settings.
        SwiftUI.Settings { SettingsView() }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "lifecycle")

    func applicationDidFinishLaunching(_ notification: Notification) {
        NotificationPresenter().requestAuthorization()
        AppEnvironment.shared.collector?.start()
        registerLoginItemIfWanted()

        // The design calls for a hidden agent, but a SwiftUI `Window` scene
        // opens at launch. Close it once the scene has been created; the app
        // survives because applicationShouldTerminateAfterLastWindowClosed
        // returns false, and applicationShouldHandleReopen brings it back.
        DispatchQueue.main.async {
            for window in NSApp.windows where window.isVisible {
                window.close()
            }
        }
    }

    /// THE load-bearing method for this whole architecture. SwiftUI terminates
    /// an app when its last window closes, and this is an LSUIElement agent
    /// with no windows by design — so without this the process exits seconds
    /// after launch, never reaches the five-minute timer, and only ever writes
    /// the single immediate poll from `start()`. launchd then respawns it,
    /// producing a stream of one-poll processes that looks like collection
    /// working while the cadence is entirely broken.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationWillTerminate(_ notification: Notification) {
        AppEnvironment.shared.collector?.stop()
    }

    /// LSUIElement hides the Dock icon, so a second launch would otherwise do
    /// nothing visible. Surfacing the detail window keeps the app reachable.
    func applicationShouldHandleReopen(_ sender: NSApplication,
                                       hasVisibleWindows: Bool) -> Bool {
        if !hasVisibleWindows {
            NSApp.windows.first?.makeKeyAndOrderFront(nil)
        }
        NSApp.activate(ignoringOtherApps: true)
        return true
    }

    private func registerLoginItemIfWanted() {
        guard AppEnvironment.shared.settings.launchAtLogin else { return }
        do {
            if SMAppService.mainApp.status != .enabled {
                try SMAppService.mainApp.register()
            }
        } catch {
            logger.warning("login item registration failed: \(String(describing: error), privacy: .public)")
        }
    }
}
