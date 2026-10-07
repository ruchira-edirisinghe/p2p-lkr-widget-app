import SwiftUI
import P2PKit

/// Makes staleness and feed failure visible. A frozen number that still looks
/// live is the failure mode this exists to prevent.
struct StatusFooter: View {
    let entry: RateEntry
    let compact: Bool

    var body: some View {
        switch entry.state {
        case .ok:
            if let sample = entry.sample {
                label("clock", Formatting.relativeAge(sample.timestamp, from: entry.date),
                      .secondary)
            }
        case .stale(let since):
            label("exclamationmark.triangle",
                  compact ? Formatting.relativeAge(since, from: entry.date)
                          : "stale · \(Formatting.relativeAge(since, from: entry.date))",
                  .orange)
        case .noData:
            label("hourglass", compact ? "no data" : "waiting for first sample", .secondary)
        case .error:
            label("bolt.horizontal.circle", compact ? "feed error" : "cannot read store", .red)
        }
    }

    private func label(_ symbol: String, _ text: String, _ color: Color) -> some View {
        HStack(spacing: 3) {
            Image(systemName: symbol).imageScale(.small)
            Text(text).lineLimit(1)
        }
        .font(.caption2)
        .foregroundStyle(color)
    }
}
