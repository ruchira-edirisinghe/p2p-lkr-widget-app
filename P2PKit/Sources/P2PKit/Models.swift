import Foundation

/// The user's action, not the advertiser's. Requesting `.sell` returns ads
/// whose own `adv.tradeType` reads "BUY" — the API states the counterparty's
/// side, so the two are always inverted.
public enum Side: String, Sendable, Codable, CaseIterable {
    case sell = "SELL"
    case buy  = "BUY"
}

public enum PaymentMethod: String, Sendable, Codable, CaseIterable {
    case bankSriLanka = "BankSriLanka"
    case bankTransfer = "BANK"

    public var displayName: String {
        switch self {
        case .bankSriLanka: "Bank Transfer (Sri Lanka)"
        case .bankTransfer: "Bank Transfer"
        }
    }
}

/// One advertisement, normalised. Fiat limits are in the quote currency (LKR),
/// never in USDT.
public struct Ad: Sendable, Equatable, Codable {
    public let price: Double
    public let availableUSDT: Double
    public let minFiat: Double
    public let maxFiat: Double
    public let payTimeLimitMinutes: Int
    public let advertiserName: String
    public let monthOrderCount: Int
    public let monthFinishRate: Double
    public let positiveRate: Double

    public init(price: Double, availableUSDT: Double, minFiat: Double, maxFiat: Double,
                payTimeLimitMinutes: Int, advertiserName: String, monthOrderCount: Int,
                monthFinishRate: Double, positiveRate: Double) {
        self.price = price
        self.availableUSDT = availableUSDT
        self.minFiat = minFiat
        self.maxFiat = maxFiat
        self.payTimeLimitMinutes = payTimeLimitMinutes
        self.advertiserName = advertiserName
        self.monthOrderCount = monthOrderCount
        self.monthFinishRate = monthFinishRate
        self.positiveRate = positiveRate
    }

    /// Whether this ad can absorb `amountUSDT` in a single order.
    public func canFill(amountUSDT: Int) -> Bool {
        let amount = Double(amountUSDT)
        guard availableUSDT >= amount else { return false }
        let fiat = amount * price
        return fiat >= minFiat && fiat <= maxFiat
    }
}

/// One observation. `fillablePrice` is nil when no ad could fill the amount —
/// a real, recordable finding, distinct from a failed poll, which stores nothing.
public struct Sample: Sendable, Equatable, Codable {
    public let timestamp: Date
    public let side: Side
    public let amountUSDT: Int
    public let fillablePrice: Double?
    public let topPrice: Double
    public let medianTop10: Double?
    public let advertiserName: String?
    public let advertiserAvailable: Double?
    public let advertiserMinFiat: Double?
    public let advertiserMaxFiat: Double?

    public init(timestamp: Date, side: Side, amountUSDT: Int, fillablePrice: Double?,
                topPrice: Double, medianTop10: Double?, advertiserName: String?,
                advertiserAvailable: Double?, advertiserMinFiat: Double?,
                advertiserMaxFiat: Double?) {
        self.timestamp = timestamp
        self.side = side
        self.amountUSDT = amountUSDT
        self.fillablePrice = fillablePrice
        self.topPrice = topPrice
        self.medianTop10 = medianTop10
        self.advertiserName = advertiserName
        self.advertiserAvailable = advertiserAvailable
        self.advertiserMinFiat = advertiserMinFiat
        self.advertiserMaxFiat = advertiserMaxFiat
    }
}

public struct SeriesPoint: Sendable, Equatable, Codable {
    public let timestamp: Date
    public let price: Double
    public init(timestamp: Date, price: Double) {
        self.timestamp = timestamp
        self.price = price
    }
}

public enum ChartWindow: String, Sendable, Codable, CaseIterable {
    case hour1  = "1h"
    case hour24 = "24h"
    case day7   = "7d"
    case day30  = "30d"

    public var duration: TimeInterval {
        switch self {
        case .hour1:  3_600
        case .hour24: 86_400
        case .day7:   604_800
        case .day30:  2_592_000
        }
    }

    /// Averaging bucket in seconds. Zero means return raw samples.
    public var bucketSeconds: Int {
        switch self {
        case .hour1, .hour24: 0
        case .day7:           3_600
        case .day30:          21_600
        }
    }
}

public enum P2PError: Error, Equatable {
    case transport(String)
    case httpStatus(Int)
    case apiCode(String, String?)
    case emptyResult
    case malformed(String)
}
