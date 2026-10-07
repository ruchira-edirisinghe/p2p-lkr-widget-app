import Testing
import Foundation
@testable import P2PKit

private func isolatedDefaults() -> UserDefaults {
    let suite = "p2p-settings-test-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return defaults
}

@Test func seedsTheDocumentedDefaults() {
    let settings = Settings(defaults: isolatedDefaults())
    #expect(settings.amountUSDT == 500)          // spec: seeded default
    #expect(settings.side == .sell)
    #expect(settings.payment == .bankSriLanka)
    #expect(settings.window == .hour24)
    #expect(settings.fiat == "LKR")
}

@Test func persistsChangedValues() {
    let defaults = isolatedDefaults()
    let settings = Settings(defaults: defaults)
    settings.amountUSDT = 2_000
    settings.side = .buy
    settings.window = .day7

    let reloaded = Settings(defaults: defaults)
    #expect(reloaded.amountUSDT == 2_000)
    #expect(reloaded.side == .buy)
    #expect(reloaded.window == .day7)
}

@Test func rejectsNonPositiveAmounts() {
    let settings = Settings(defaults: isolatedDefaults())
    settings.amountUSDT = 0
    #expect(settings.amountUSDT == 500)
    settings.amountUSDT = -100
    #expect(settings.amountUSDT == 500)
}

@Test func fallsBackWhenStoredEnumIsUnrecognised() {
    let defaults = isolatedDefaults()
    defaults.set("SIDEWAYS", forKey: "side")
    defaults.set("99y", forKey: "window")
    let settings = Settings(defaults: defaults)
    #expect(settings.side == .sell)
    #expect(settings.window == .hour24)
}
