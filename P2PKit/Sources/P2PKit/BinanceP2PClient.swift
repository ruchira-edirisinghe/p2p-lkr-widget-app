import Foundation

public struct BinanceP2PClient: Sendable {
    public static let endpoint = URL(string:
        "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search")!

    /// The page sends a browser UA; an obviously scripted one invites blocking.
    static let userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                           "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    /// Mirrors the query parameters of
    /// p2p.binance.com/trade/sell/USDT?fiat=LKR&payment=BankSriLanka
    public static func requestBody(side: Side, fiat: String,
                                   payment: PaymentMethod, rows: Int) -> [String: Any] {
        [
            "fiat": fiat,
            "asset": "USDT",
            "tradeType": side.rawValue,
            "payTypes": [payment.rawValue],
            "page": 1,
            "rows": rows,
            "countries": [],
            "periods": [],
            "proMerchantAds": false,
            "shieldMerchantAds": false,
            "filterType": "all",
            "additionalKycVerifyFilter": 0,
            "publisherType": NSNull(),
            "classifies": ["mass", "profession", "fiat_trade"],
        ]
    }

    public func search(side: Side, fiat: String = "LKR",
                       payment: PaymentMethod = .bankSriLanka,
                       rows: Int = 20) async throws -> [Ad] {
        var request = URLRequest(url: Self.endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        request.httpBody = try JSONSerialization.data(
            withJSONObject: Self.requestBody(side: side, fiat: fiat, payment: payment, rows: rows))

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw P2PError.transport(error.localizedDescription)
        }

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw P2PError.httpStatus(http.statusCode)
        }
        return try WireFormat.decodeAds(from: data)
    }
}
