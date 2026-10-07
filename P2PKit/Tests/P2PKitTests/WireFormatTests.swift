import Testing
import Foundation
@testable import P2PKit

@Test func decodesSellFixtureIntoTwentyAds() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(ads.count == 20)
}

@Test func decodesStringNumbersAndPreservesOrder() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    // Verified ground truth: the top-of-book ad demands a 499,999 LKR minimum.
    #expect(ads[0].price == 332.00)
    #expect(ads[0].availableUSDT == 1510.00)
    #expect(ads[0].minFiat == 499_999)
    #expect(ads[0].maxFiat == 500_000)
    #expect(ads[0].advertiserName == "jeewani 98")
    #expect(ads[0].payTimeLimitMinutes == 15)
}

@Test func sellAdsArriveBestFirstDescending() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    #expect(ads.map(\.price) == ads.map(\.price).sorted(by: >))
}

@Test func buyAdsArriveBestFirstAscending() throws {
    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.buy))
    #expect(ads.map(\.price) == ads.map(\.price).sorted(by: <))
}

@Test func rejectsNonSuccessApiCode() throws {
    let body = Data(#"{"code":"000002","message":"rate limited","data":[]}"#.utf8)
    #expect(throws: P2PError.apiCode("000002", "rate limited")) {
        try WireFormat.decodeAds(from: body)
    }
}

@Test func reportsEmptyResultDistinctly() throws {
    let body = Data(#"{"code":"000000","message":null,"data":[]}"#.utf8)
    #expect(throws: P2PError.emptyResult) {
        try WireFormat.decodeAds(from: body)
    }
}

@Test func reportsMalformedJsonDistinctly() throws {
    #expect(throws: (any Error).self) {
        try WireFormat.decodeAds(from: Data("not json".utf8))
    }
}

@Test func skipsIndividualAdsWithUnparseableNumbers() throws {
    // One bad ad must not discard a whole otherwise-valid response.
    let body = Data("""
    {"code":"000000","data":[
      {"adv":{"price":"oops","tradableQuantity":"1","minSingleTransAmount":"1",
              "dynamicMaxSingleTransAmount":"2","payTimeLimit":15},
       "advertiser":{"nickName":"bad","monthOrderCount":1,"monthFinishRate":1,"positiveRate":1}},
      {"adv":{"price":"330.50","tradableQuantity":"700","minSingleTransAmount":"1000",
              "dynamicMaxSingleTransAmount":"200000","payTimeLimit":15},
       "advertiser":{"nickName":"good","monthOrderCount":5,"monthFinishRate":0.99,"positiveRate":1}}
    ]}
    """.utf8)
    let ads = try WireFormat.decodeAds(from: body)
    #expect(ads.count == 1)
    #expect(ads[0].advertiserName == "good")
}
