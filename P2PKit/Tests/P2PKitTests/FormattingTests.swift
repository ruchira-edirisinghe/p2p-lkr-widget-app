import Testing
import Foundation
@testable import P2PKit

@Test func priceAlwaysShowsTwoDecimals() {
    #expect(Formatting.price(330.0) == "330.00")
    #expect(Formatting.price(331.0) == "331.00")
    // 330.765 is not exactly representable: the nearest double is
    // 330.764999999999986…, so %.2f gives 330.76. Asserting "330.77" would be
    // asserting against IEEE 754 rather than against this code. Exact .xx5
    // ties are deliberately not tested — they describe the platform, not us.
    #expect(Formatting.price(330.765) == "330.76")
    #expect(Formatting.price(330.7678) == "330.77")
    #expect(Formatting.price(nil) == "—")
}

@Test func deltaCarriesAnExplicitSign() {
    #expect(Formatting.signedDelta(0.34) == "+0.34")
    #expect(Formatting.signedDelta(-0.34) == "-0.34")
    #expect(Formatting.signedDelta(0) == "0.00")
}

@Test func percentIsSignedToTwoDecimals() {
    #expect(Formatting.percent(0.1027) == "+0.10%")
    #expect(Formatting.percent(-1.5) == "-1.50%")
}

@Test func usdtAmountsAreGrouped() {
    #expect(Formatting.usdt(8000) == "8,000 USDT")
    #expect(Formatting.usdt(500) == "500 USDT")
}

@Test func fiatAmountsAreGroupedWithoutDecimals() {
    #expect(Formatting.fiat(220_000) == "220,000")
    #expect(Formatting.fiat(499_999) == "499,999")
}

@Test func relativeAgeReadsInPlainWords() {
    let now = Date(timeIntervalSince1970: 10_000)
    #expect(Formatting.relativeAge(now.addingTimeInterval(-30), from: now) == "just now")
    #expect(Formatting.relativeAge(now.addingTimeInterval(-120), from: now) == "2m ago")
    #expect(Formatting.relativeAge(now.addingTimeInterval(-7_200), from: now) == "2h ago")
    #expect(Formatting.relativeAge(now.addingTimeInterval(-172_800), from: now) == "2d ago")
}
