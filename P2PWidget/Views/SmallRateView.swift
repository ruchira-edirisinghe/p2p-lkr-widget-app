import SwiftUI
import P2PKit

struct SmallRateView: View {
    let entry: RateEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("USDT → LKR")
                .font(.caption2.weight(.medium))
                .foregroundStyle(.secondary)
            RateHeader(entry: entry, showsCaption: false)
            RateSparkline(series: entry.series,
                          direction: entry.trend?.direction,
                          showsAxes: false)
                .frame(maxHeight: .infinity)
            StatusFooter(entry: entry, compact: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
