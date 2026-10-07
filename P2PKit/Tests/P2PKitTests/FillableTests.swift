import Testing
import Foundation
@testable import P2PKit

@Test func sellAt500SkipsTheUntradeableTopOfBook() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 500))
    // Top of book is 332.00 but demands a 499,999 LKR minimum (~1,500 USDT).
    #expect(best.price == 331.00)
    #expect(best.advertiserName == "TD_TrustPay_LK")
}

@Test func rejectsSamePricedAdThatLacksStock() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 500))
    // Two ads quote 331.00; the earlier one holds only 100 USDT. Proves the
    // filter is not a price sort.
    #expect(best.availableUSDT == 700.00)
}

@Test func sellAtLargerAmountPicksADeeperAd() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 2000))
    #expect(best.price == 330.67)
    #expect(best.advertiserName == "HASSY-THECRYPTOQUEEN")
}

@Test func buySideUsesTheSameFirstMatchRule() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.buy))
    let best = try #require(MetricsEngine.fillable(ads, amountUSDT: 500))
    #expect(best.price == 331.49)
    #expect(best.advertiserName == "Alilruben")
}

@Test func returnsNilWhenNoAdCanFill() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(MetricsEngine.fillable(ads, amountUSDT: 10_000_000) == nil)
}

@Test func returnsNilForAnEmptyBook() {
    #expect(MetricsEngine.fillable([], amountUSDT: 500) == nil)
}

@Test func topPriceIsTheRawFirstAd() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(MetricsEngine.topPrice(ads) == 332.00)
}

@Test func medianOfTopTenAveragesTheMiddlePair() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let median = try #require(MetricsEngine.medianTop10(ads))
    #expect(abs(median - 330.765) < 0.0001)
}

@Test func medianHandlesFewerThanTenAds() {
    let ads = [330.0, 331.0, 332.0].map { price in
        Ad(price: price, availableUSDT: 1000, minFiat: 0, maxFiat: 1_000_000,
           payTimeLimitMinutes: 15, advertiserName: "x", monthOrderCount: 1,
           monthFinishRate: 1, positiveRate: 1)
    }
    #expect(MetricsEngine.medianTop10(ads) == 331.0)
}

@Test func sampleRecordsNilFillableButKeepsTopOfBook() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let sample = try #require(MetricsEngine.makeSample(
        ads: ads, side: .sell, amountUSDT: 10_000_000, timestamp: .now))
    // "No ad could fill it" is a finding worth storing, not a failure.
    #expect(sample.fillablePrice == nil)
    #expect(sample.topPrice == 332.00)
    #expect(sample.advertiserName == nil)
}

@Test func sampleCapturesWinningAdvertiserDetails() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let sample = try #require(MetricsEngine.makeSample(
        ads: ads, side: .sell, amountUSDT: 500, timestamp: .now))
    #expect(sample.fillablePrice == 331.00)
    #expect(sample.advertiserName == "TD_TrustPay_LK")
    #expect(sample.advertiserAvailable == 700.00)
    #expect(sample.advertiserMinFiat == 10_000)
    #expect(sample.advertiserMaxFiat == 220_000)
}
