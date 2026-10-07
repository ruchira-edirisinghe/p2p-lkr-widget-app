import Testing
import Foundation
@testable import P2PKit

/// Serialized because `StubURLProtocol` holds process-global state. Run in
/// parallel these tests read each other's stubbed responses.
@Suite(.serialized)
struct BinanceP2PClientTests {

    @Test func requestBodyMapsUrlParametersOntoJsonFields() {
        let body = BinanceP2PClient.requestBody(side: .sell, fiat: "LKR",
                                                payment: .bankSriLanka, rows: 20)
        #expect(body["fiat"] as? String == "LKR")
        #expect(body["asset"] as? String == "USDT")
        // Request tradeType is the *user's* action.
        #expect(body["tradeType"] as? String == "SELL")
        #expect(body["payTypes"] as? [String] == ["BankSriLanka"])
        #expect(body["rows"] as? Int == 20)
        #expect(body["page"] as? Int == 1)
    }

    @Test func searchDecodesStubbedFixture() async throws {
        StubURLProtocol.install(body: try Fixture.data(Fixture.sell))
        let client = BinanceP2PClient(session: StubURLProtocol.session())
        let ads = try await client.search(side: .sell, fiat: "LKR",
                                         payment: .bankSriLanka, rows: 20)
        #expect(ads.count == 20)
        #expect(ads[0].price == 332.00)
    }

    @Test func searchSendsTradeTypeMatchingRequestedSide() async throws {
        StubURLProtocol.install(body: try Fixture.data(Fixture.buy))
        let client = BinanceP2PClient(session: StubURLProtocol.session())
        _ = try await client.search(side: .buy, fiat: "LKR",
                                    payment: .bankSriLanka, rows: 20)
        let sent = try #require(StubURLProtocol.lastRequestBody)
        let json = try #require(try JSONSerialization.jsonObject(with: sent) as? [String: Any])
        #expect(json["tradeType"] as? String == "BUY")
    }

    @Test func mapsNonSuccessHttpStatusToHttpStatusError() async throws {
        StubURLProtocol.install(status: 503)
        let client = BinanceP2PClient(session: StubURLProtocol.session())
        await #expect(throws: P2PError.httpStatus(503)) {
            try await client.search(side: .sell, fiat: "LKR",
                                    payment: .bankSriLanka, rows: 20)
        }
    }

    @Test func mapsTransportFailureToTransportError() async throws {
        StubURLProtocol.install(error: URLError(.notConnectedToInternet))
        let client = BinanceP2PClient(session: StubURLProtocol.session())
        await #expect(throws: (any Error).self) {
            try await client.search(side: .sell, fiat: "LKR",
                                    payment: .bankSriLanka, rows: 20)
        }
    }
}
