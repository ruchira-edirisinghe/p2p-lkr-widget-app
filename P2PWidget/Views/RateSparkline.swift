import SwiftUI
import Charts
import P2PKit

/// Gaps in `series` stay gaps: the collector writes no row for a failed poll,
/// and a connected line there would imply a rate that never existed.
struct RateSparkline: View {
    let series: [SeriesPoint]
    let direction: Trend.Direction?
    let showsAxes: Bool

    private var lineColor: Color {
        switch direction {
        case .up:   .green
        case .down: .red
        default:    .accentColor
        }
    }

    /// A gap longer than this breaks the line.
    private var gapThreshold: TimeInterval {
        guard series.count > 2 else { return .greatestFiniteMagnitude }
        let deltas = zip(series, series.dropFirst()).map {
            $1.timestamp.timeIntervalSince($0.timestamp)
        }
        let median = deltas.sorted()[deltas.count / 2]
        return max(median * 3, 60)
    }

    /// Splits the series wherever a sampling gap occurs so Charts draws
    /// separate lines rather than bridging the hole.
    private var segments: [[SeriesPoint]] {
        var result: [[SeriesPoint]] = []
        var current: [SeriesPoint] = []
        for point in series {
            if let last = current.last,
               point.timestamp.timeIntervalSince(last.timestamp) > gapThreshold {
                result.append(current)
                current = []
            }
            current.append(point)
        }
        if !current.isEmpty { result.append(current) }
        return result
    }

    var body: some View {
        if series.count < 2 {
            Text("Collecting history")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Chart {
                ForEach(Array(segments.enumerated()), id: \.offset) { index, segment in
                    ForEach(segment, id: \.timestamp) { point in
                        LineMark(x: .value("Time", point.timestamp),
                                 y: .value("Price", point.price),
                                 series: .value("Segment", index))
                        .interpolationMethod(.monotone)
                        .foregroundStyle(lineColor)
                        .lineStyle(StrokeStyle(lineWidth: 1.8))
                    }
                }
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXAxis(showsAxes ? .automatic : .hidden)
            .chartYAxis(showsAxes ? .automatic : .hidden)
        }
    }
}
