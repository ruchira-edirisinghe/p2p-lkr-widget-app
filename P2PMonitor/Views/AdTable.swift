import SwiftUI
import P2PKit

/// Table needs Identifiable rows, and `Ad` has no natural identity — position
/// in the returned book is the only stable key.
private struct AdRow: Identifiable {
    let id: Int
    let ad: Ad
}

struct AdTable: View {
    let ads: [Ad]
    let amountUSDT: Int

    private var rows: [AdRow] {
        ads.enumerated().map { AdRow(id: $0.offset, ad: $0.element) }
    }

    var body: some View {
        Table(rows) {
            TableColumn("Price") { row in
                Text(Formatting.price(row.ad.price)).monospacedDigit()
            }
            .width(min: 64, ideal: 72)

            TableColumn("Available") { row in
                Text(Formatting.usdt(row.ad.availableUSDT)).monospacedDigit()
            }
            .width(min: 90, ideal: 100)

            TableColumn("Limit (LKR)") { row in
                Text("\(Formatting.fiat(row.ad.minFiat)) – \(Formatting.fiat(row.ad.maxFiat))")
                    .monospacedDigit()
            }
            .width(min: 140, ideal: 170)

            TableColumn("Advertiser") { row in
                Text(row.ad.advertiserName).lineLimit(1)
            }

            TableColumn("Orders") { row in
                Text("\(row.ad.monthOrderCount)").monospacedDigit()
            }
            .width(min: 56, ideal: 64)

            // The point of the whole app: which ads your size can actually use.
            TableColumn("Fills \(Formatting.usdt(amountUSDT))") { row in
                if row.ad.canFill(amountUSDT: amountUSDT) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                } else {
                    Image(systemName: "minus").foregroundStyle(.tertiary)
                }
            }
            .width(min: 96, ideal: 110)
        }
    }
}
