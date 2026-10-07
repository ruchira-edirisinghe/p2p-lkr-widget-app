import SwiftUI
import P2PKit

struct MediumRateView: View {
    let entry: RateEntry

    private var low: Double? { entry.series.map(\.price).min() }
    private var high: Double? { entry.series.map(\.price).max() }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                RateHeader(entry: entry, showsCaption: true)
                Spacer()
                Text("P2P · \(entry.window.rawValue)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            RateSparkline(series: entry.series,
                          direction: entry.trend?.direction,
                          showsAxes: false)
                .frame(maxHeight: .infinity)

            HStack(spacing: 10) {
                if let low, let high {
                    Text("low \(Formatting.price(low))")
                    Text("high \(Formatting.price(high))")
                }
                Spacer()
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .monospacedDigit()

            if let name = entry.sample?.advertiserName,
               let available = entry.sample?.advertiserAvailable {
                Text("\(name) · \(Formatting.usdt(available))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            StatusFooter(entry: entry, compact: false)
        }
    }
}
