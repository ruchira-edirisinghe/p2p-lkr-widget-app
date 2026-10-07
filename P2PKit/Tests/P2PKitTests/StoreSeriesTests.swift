import Testing
import Foundation
@testable import P2PKit

private func seriesStore() throws -> (Store, URL) {
    let url = URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("p2p-series-\(UUID().uuidString).sqlite")
    return (try Store(fileURL: url), url)
}

private func makeSample(_ ts: TimeInterval, _ price: Double?) -> Sample {
    Sample(timestamp: Date(timeIntervalSince1970: ts), side: .sell, amountUSDT: 500,
           fillablePrice: price, topPrice: 332.0, medianTop10: nil, advertiserName: "x",
           advertiserAvailable: 700, advertiserMinFiat: 1_000, advertiserMaxFiat: 220_000)
}

@Test func seriesReturnsOnlyRowsInsideTheWindowAscending() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 100_000)

    try store.append(makeSample(100_000 - 7_200, 320.0))  // outside 1h
    try store.append(makeSample(100_000 - 1_800, 330.0))  // inside
    try store.append(makeSample(100_000 - 600,   331.0))  // inside

    let series = try store.series(side: .sell, amountUSDT: 500, window: .hour1, now: now)
    #expect(series.map(\.price) == [330.0, 331.0])
}

@Test func seriesOmitsRowsWithNoFillablePrice() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 100_000)

    try store.append(makeSample(100_000 - 1_800, 330.0))
    try store.append(makeSample(100_000 - 1_200, nil))    // nothing fillable then
    try store.append(makeSample(100_000 - 600,   331.0))

    // A price the chart cannot plot must not become a zero or a flat carry.
    let series = try store.series(side: .sell, amountUSDT: 500, window: .hour1, now: now)
    #expect(series.map(\.price) == [330.0, 331.0])
}

@Test func sevenDayWindowIsBucketedHourly() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 700_000)

    // Two samples inside one hour bucket, one in the next.
    try store.append(makeSample(690_000, 330.0))
    try store.append(makeSample(690_600, 332.0))
    try store.append(makeSample(694_000, 340.0))

    let series = try store.series(side: .sell, amountUSDT: 500, window: .day7, now: now)
    #expect(series.count == 2)
    #expect(series[0].price == 331.0)
    #expect(series[1].price == 340.0)
}

@Test func seriesIsScopedBySideAndAmount() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    let now = Date(timeIntervalSince1970: 100_000)

    try store.append(makeSample(99_000, 330.0))
    try store.append(Sample(timestamp: Date(timeIntervalSince1970: 99_000), side: .buy,
                            amountUSDT: 500, fillablePrice: 999.0, topPrice: 999.0,
                            medianTop10: nil, advertiserName: nil, advertiserAvailable: nil,
                            advertiserMinFiat: nil, advertiserMaxFiat: nil))

    let series = try store.series(side: .sell, amountUSDT: 500, window: .hour1, now: now)
    #expect(series.map(\.price) == [330.0])
}

@Test func snapshotRoundTripsTheAdList() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }

    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    let captured = Date(timeIntervalSince1970: 100_000)
    try store.replaceSnapshot(side: .sell, ads: ads, capturedAt: captured)

    let got = try #require(try store.snapshot(side: .sell))
    #expect(got.capturedAt == captured)
    #expect(got.ads.count == 20)
    #expect(got.ads[0].advertiserName == "jeewani 98")
}

@Test func replacingASnapshotDoesNotAccumulateRows() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }

    let ads = try WireFormat.decodeAds(from: try Fixture.data(Fixture.sell))
    try store.replaceSnapshot(side: .sell, ads: ads, capturedAt: Date(timeIntervalSince1970: 1))
    try store.replaceSnapshot(side: .sell, ads: Array(ads.prefix(3)),
                              capturedAt: Date(timeIntervalSince1970: 2))

    let got = try #require(try store.snapshot(side: .sell))
    #expect(got.ads.count == 3)
    #expect(got.capturedAt == Date(timeIntervalSince1970: 2))
}

@Test func snapshotIsNilBeforeAnyCapture() throws {
    let (store, url) = try seriesStore()
    defer { try? FileManager.default.removeItem(at: url) }
    #expect(try store.snapshot(side: .buy) == nil)
}
