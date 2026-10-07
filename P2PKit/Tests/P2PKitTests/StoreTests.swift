import Testing
import Foundation
@testable import P2PKit

private func tempStore() throws -> (Store, URL) {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-test-\(UUID().uuidString).sqlite")
    return (try Store(fileURL: url), url)
}

private func sample(_ ts: TimeInterval, _ price: Double?, amount: Int = 500,
                    side: Side = .sell) -> Sample {
    Sample(timestamp: Date(timeIntervalSince1970: ts), side: side, amountUSDT: amount,
           fillablePrice: price, topPrice: 332.0, medianTop10: 330.765,
           advertiserName: price == nil ? nil : "TD_TrustPay_LK",
           advertiserAvailable: 700, advertiserMinFiat: 10_000, advertiserMaxFiat: 220_000)
}

@Test func appendThenReadBackRoundTrips() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 331.00))
    let got = try #require(try store.latest(side: .sell, amountUSDT: 500))
    #expect(got.fillablePrice == 331.00)
    #expect(got.topPrice == 332.0)
    #expect(got.advertiserName == "TD_TrustPay_LK")
    #expect(got.advertiserMaxFiat == 220_000)
    #expect(got.timestamp == Date(timeIntervalSince1970: 1_000))
}

@Test func latestReturnsTheNewestRow() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 330.00))
    try store.append(sample(2_000, 331.00))
    try store.append(sample(1_500, 330.50))
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
}

@Test func nilFillablePriceSurvivesTheRoundTrip() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, nil))
    let got = try #require(try store.latest(side: .sell, amountUSDT: 500))
    // Distinct from a failed poll, which stores no row at all.
    #expect(got.fillablePrice == nil)
    #expect(got.topPrice == 332.0)
}

@Test func latestIsScopedBySideAndAmount() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 331.00, amount: 500, side: .sell))
    try store.append(sample(1_000, 331.49, amount: 500, side: .buy))
    try store.append(sample(1_000, 330.67, amount: 2_000, side: .sell))

    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
    #expect(try store.latest(side: .buy,  amountUSDT: 500)?.fillablePrice == 331.49)
    #expect(try store.latest(side: .sell, amountUSDT: 2_000)?.fillablePrice == 330.67)
    #expect(try store.latest(side: .buy,  amountUSDT: 9_999) == nil)
}

@Test func appendingTheSameKeyTwiceReplacesRatherThanDuplicates() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 330.00))
    try store.append(sample(1_000, 331.00))
    #expect(try store.sampleCount() == 1)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 331.00)
}

@Test func pruneDeletesOnlyRowsStrictlyOlderThanTheCutoff() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }

    try store.append(sample(1_000, 330.00))
    try store.append(sample(2_000, 331.00))
    try store.append(sample(3_000, 332.00))

    let deleted = try store.prune(olderThan: Date(timeIntervalSince1970: 2_000))
    #expect(deleted == 1)
    #expect(try store.sampleCount() == 2)
    #expect(try store.latest(side: .sell, amountUSDT: 500)?.fillablePrice == 332.00)
}

@Test func reopeningAnExistingFileKeepsItsRows() throws {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-reopen-\(UUID().uuidString).sqlite")
    defer { try? FileManager.default.removeItem(at: url) }

    do {
        let store = try Store(fileURL: url)
        try store.append(sample(1_000, 331.00))
    }
    let reopened = try Store(fileURL: url)
    #expect(try reopened.sampleCount() == 1)
}

@Test func walModeIsEnabledSoTheWidgetCanReadDuringWrites() throws {
    let (store, url) = try tempStore()
    defer { try? FileManager.default.removeItem(at: url) }
    #expect(try store.journalMode().lowercased() == "wal")
}
