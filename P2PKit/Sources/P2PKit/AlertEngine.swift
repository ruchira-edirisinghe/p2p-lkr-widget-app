import Foundation

public enum ThresholdDirection: String, Sendable, Codable, CaseIterable {
    case above, below
}

public enum AlertState: String, Sendable, Codable {
    /// Waiting for a crossing.
    case armed
    /// Already notified; will not notify again until the price crosses back.
    case triggered
}

public struct AlertRule: Sendable, Codable, Equatable, Identifiable {
    public let id: String
    public let side: Side
    public let amountUSDT: Int
    public let threshold: Double
    public let direction: ThresholdDirection

    public init(id: String = UUID().uuidString, side: Side, amountUSDT: Int,
                threshold: Double, direction: ThresholdDirection) {
        self.id = id
        self.side = side
        self.amountUSDT = amountUSDT
        self.threshold = threshold
        self.direction = direction
    }
}

public struct AlertDecision: Sendable, Equatable {
    public let fire: Bool
    public let newState: AlertState
}

public enum AlertEngine {
    /// Edge-triggered, not level-triggered: a notification fires on the
    /// transition into the threshold and the rule then re-arms only once the
    /// price crosses back. Without this the collector would notify every five
    /// minutes for as long as the rate stayed past the threshold.
    public static func evaluate(rule: AlertRule, price: Double,
                                state: AlertState) -> AlertDecision {
        let beyond = switch rule.direction {
        case .above: price >= rule.threshold
        case .below: price <= rule.threshold
        }

        switch (beyond, state) {
        case (true,  .armed):     return AlertDecision(fire: true,  newState: .triggered)
        case (true,  .triggered): return AlertDecision(fire: false, newState: .triggered)
        case (false, .triggered): return AlertDecision(fire: false, newState: .armed)
        case (false, .armed):     return AlertDecision(fire: false, newState: .armed)
        }
    }
}
