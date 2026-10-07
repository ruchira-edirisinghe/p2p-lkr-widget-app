import Foundation

public struct Trend: Sendable, Equatable {
    public enum Direction: Sendable, Equatable { case up, down, flat }

    public let previous: Double
    public let current: Double

    public init(previous: Double, current: Double) {
        self.previous = previous
        self.current = current
    }

    public var delta: Double { current - previous }

    public var percent: Double {
        previous == 0 ? 0 : (delta / previous) * 100
    }

    public var direction: Direction {
        if delta > 0 { return .up }
        if delta < 0 { return .down }
        return .flat
    }
}
