import Foundation

/// Preferences shared between the app and the widget through the App Group
/// defaults suite. Widget instances may override side, amount, and window via
/// their own AppIntent configuration; these are the global fallbacks.
public final class Settings: @unchecked Sendable {
    public enum Defaults {
        /// Seeded amount from the design decisions. Roughly 165,000 LKR, which
        /// several ads in the recorded book can fill.
        public static let amountUSDT = 500
        public static let side = Side.sell
        public static let payment = PaymentMethod.bankSriLanka
        public static let window = ChartWindow.hour24
        public static let fiat = "LKR"
        public static let pollInterval: TimeInterval = 300
        public static let jitter: TimeInterval = 20
        public static let retentionDays = 30
        /// Beyond this age the widget renders a stale state.
        public static let stalenessThreshold: TimeInterval = 900
    }

    private let defaults: UserDefaults

    public static let shared = Settings(
        defaults: UserDefaults(suiteName: AppGroup.identifier) ?? .standard)

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    public var amountUSDT: Int {
        get {
            let stored = defaults.integer(forKey: "amountUSDT")
            return stored > 0 ? stored : Defaults.amountUSDT
        }
        // A non-positive amount cannot be filled by any ad, so refuse it
        // rather than storing a value that guarantees an empty chart.
        set { if newValue > 0 { defaults.set(newValue, forKey: "amountUSDT") } }
    }

    public var side: Side {
        get { defaults.string(forKey: "side").flatMap { Side(rawValue: $0) } ?? Defaults.side }
        set { defaults.set(newValue.rawValue, forKey: "side") }
    }

    public var payment: PaymentMethod {
        get {
            defaults.string(forKey: "payment")
                .flatMap { PaymentMethod(rawValue: $0) } ?? Defaults.payment
        }
        set { defaults.set(newValue.rawValue, forKey: "payment") }
    }

    public var window: ChartWindow {
        get {
            defaults.string(forKey: "window")
                .flatMap { ChartWindow(rawValue: $0) } ?? Defaults.window
        }
        set { defaults.set(newValue.rawValue, forKey: "window") }
    }

    public var fiat: String {
        get { defaults.string(forKey: "fiat") ?? Defaults.fiat }
        set { defaults.set(newValue, forKey: "fiat") }
    }

    public var launchAtLogin: Bool {
        get { defaults.object(forKey: "launchAtLogin") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "launchAtLogin") }
    }
}
