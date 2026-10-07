import Foundation
import Testing

enum Fixture {
    static func data(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: "Fixtures/\(name)", withExtension: "json") else {
            throw P2PErrorForTests.missingFixture(name)
        }
        return try Data(contentsOf: url)
    }
    static let sell = "lkr-sell-20260907"
    static let buy  = "lkr-buy-20260907"
}

enum P2PErrorForTests: Error { case missingFixture(String) }
