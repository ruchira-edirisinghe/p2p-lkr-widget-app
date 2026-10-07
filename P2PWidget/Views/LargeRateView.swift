import SwiftUI
import P2PKit

struct LargeRateView: View {
    let entry: RateEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                RateHeader(entry: entry, showsCaption: true)
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text("P2P · \(entry.window.rawValue)")
                    if let top = entry.sample?.topPrice {
                        // Shown for contrast: the headline is the fillable
                        // price, which is often lower than this.
                        Text("top of book \(Formatting.price(top))")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            RateSparkline(series: entry.series,
                          direction: entry.trend?.direction,
                          showsAxes: true)
                .frame(minHeight: 90)

            Divider()

            if entry.topAds.isEmpty {
                Text("No ad book captured yet")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 3) {
                    ForEach(Array(entry.topAds.enumerated()), id: \.offset) { _, ad in
                        HStack(spacing: 6) {
                            Text(Formatting.price(ad.price))
                                .monospacedDigit()
                                .frame(width: 54, alignment: .leading)
                            Text(Formatting.usdt(ad.availableUSDT))
                                .monospacedDigit()
                                .frame(width: 82, alignment: .leading)
                                .foregroundStyle(.secondary)
                            Text(ad.advertiserName)
                                .lineLimit(1)
                                .truncationMode(.tail)
                            Spacer()
                            if ad.canFill(amountUSDT: entry.amountUSDT) {
                                Image(systemName: "checkmark.circle.fill")
                                    .imageScale(.small)
                                    .foregroundStyle(.green)
                            }
                        }
                        .font(.caption2)
                    }
                }
            }

            Spacer(minLength: 0)
            StatusFooter(entry: entry, compact: false)
        }
    }
}
