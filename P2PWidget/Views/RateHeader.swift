import SwiftUI
import P2PKit

/// Price plus direction. SF Symbols, never emoji.
struct RateHeader: View {
    let entry: RateEntry
    let showsCaption: Bool

    private var directionColor: Color {
        switch entry.trend?.direction {
        case .up:   .green
        case .down: .red
        default:    .secondary
        }
    }

    private var directionSymbol: String {
        switch entry.trend?.direction {
        case .up:   "arrow.up.right"
        case .down: "arrow.down.right"
        default:    "minus"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(Formatting.price(entry.sample?.fillablePrice))
                    .font(.system(.title2, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                if let trend = entry.trend {
                    HStack(spacing: 2) {
                        Image(systemName: directionSymbol).imageScale(.small)
                        Text(Formatting.percent(trend.percent)).monospacedDigit()
                    }
                    .font(.caption.weight(.medium))
                    .foregroundStyle(directionColor)
                }
            }
            if showsCaption {
                Text("\(entry.side == .sell ? "Sell" : "Buy") \(Formatting.usdt(entry.amountUSDT))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
