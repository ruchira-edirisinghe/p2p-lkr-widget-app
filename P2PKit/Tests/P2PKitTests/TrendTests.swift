import Testing
import Foundation
@testable import P2PKit

private func points(_ values: [(Int, Double)]) -> [SeriesPoint] {
    values.map { SeriesPoint(timestamp: Date(timeIntervalSince1970: TimeInterval($0.0)),
                             price: $0.1) }
}

@Test func trendRisesFromFirstToLast() throws {
    let t = try #require(MetricsEngine.trend(series: points([(0, 330.0), (60, 331.5)])))
    #expect(t.previous == 330.0)
    #expect(t.current == 331.5)
    #expect(abs(t.delta - 1.5) < 0.0001)
    #expect(abs(t.percent - 0.4545) < 0.001)
    #expect(t.direction == .up)
}

@Test func trendFallsWhenPriceDrops() throws {
    let t = try #require(MetricsEngine.trend(series: points([(0, 331.5), (60, 330.0)])))
    #expect(t.direction == .down)
    #expect(t.delta < 0)
}

@Test func trendIsFlatWhenUnchanged() throws {
    let t = try #require(MetricsEngine.trend(series: points([(0, 331.0), (60, 331.0)])))
    #expect(t.direction == .flat)
    #expect(t.delta == 0)
}

@Test func trendNeedsAtLeastTwoPoints() {
    #expect(MetricsEngine.trend(series: points([(0, 331.0)])) == nil)
    #expect(MetricsEngine.trend(series: []) == nil)
}

@Test func downsampleAveragesWithinBuckets() {
    // Two 3600s buckets: [0,3600) averages 330 and 332 to 331.
    let raw = points([(0, 330.0), (1800, 332.0), (3600, 340.0), (5400, 342.0)])
    let out = MetricsEngine.downsample(raw, bucketSeconds: 3600)
    #expect(out.count == 2)
    #expect(out[0].price == 331.0)
    #expect(out[1].price == 341.0)
    #expect(out[0].timestamp == Date(timeIntervalSince1970: 0))
    #expect(out[1].timestamp == Date(timeIntervalSince1970: 3600))
}

@Test func downsampleWithZeroBucketReturnsRawSeries() {
    let raw = points([(0, 330.0), (300, 331.0)])
    #expect(MetricsEngine.downsample(raw, bucketSeconds: 0) == raw)
}

@Test func downsamplePreservesGapsRatherThanInterpolating() {
    // A 24h hole between the two clusters must not become synthetic buckets:
    // overnight sleep gaps have to read as missing data, never as a flat rate.
    let raw = points([(0, 330.0), (3600, 331.0), (90_000, 340.0)])
    let out = MetricsEngine.downsample(raw, bucketSeconds: 3600)
    #expect(out.count == 3)
    // 90_000 is exactly 25 buckets of 3600, so its bucket key is 90_000 itself.
    let stamps = out.map { Int($0.timestamp.timeIntervalSince1970) }
    #expect(stamps == [0, 3600, 90_000])
}
