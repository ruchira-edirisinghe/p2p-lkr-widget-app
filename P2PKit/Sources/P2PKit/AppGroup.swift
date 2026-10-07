import Foundation

/// Locations shared between the collector app and the read-only widget.
public enum AppGroup {
    /// Team-ID prefix is required for Developer ID (non-App-Store) distribution.
    public static let identifier = "UN798LFFKG.group.dev.dfanso.p2pmonitor"

    /// Nil when the calling process is not signed with the app-group
    /// entitlement — notably in unit tests.
    public static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    public static var databaseURL: URL? {
        containerURL?.appendingPathComponent("p2p.sqlite")
    }
}
