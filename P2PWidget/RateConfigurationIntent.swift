import AppIntents
import WidgetKit
import P2PKit

// AppIntents cannot use enums declared in another module: the metadata
// extractor rejects them with "enums implemented in an imported framework or
// library are not supported", and it also requires the display representations
// be compile-time constants rather than computed properties. So the intent
// exposes local mirrors and maps to the P2PKit types at the boundary, which
// keeps P2PKit free of any AppIntents dependency.

enum SideOption: String, AppEnum {
    case sell, buy

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Side"
    static let caseDisplayRepresentations: [SideOption: DisplayRepresentation] = [
        .sell: "Sell USDT",
        .buy: "Buy USDT",
    ]

    var asSide: Side {
        switch self {
        case .sell: .sell
        case .buy:  .buy
        }
    }
}

enum WindowOption: String, AppEnum {
    case hour1, hour24, day7, day30

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Window"
    static let caseDisplayRepresentations: [WindowOption: DisplayRepresentation] = [
        .hour1:  "1 hour",
        .hour24: "24 hours",
        .day7:   "7 days",
        .day30:  "30 days",
    ]

    var asWindow: ChartWindow {
        switch self {
        case .hour1:  .hour1
        case .hour24: .hour24
        case .day7:   .day7
        case .day30:  .day30
        }
    }
}

struct RateConfigurationIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "USDT/LKR Rate"
    static let description = IntentDescription("Best P2P rate that can fill your trade size.")

    @Parameter(title: "Side", default: .sell)
    var side: SideOption

    /// The amount decides which ads even qualify, so it is the most important
    /// knob on the widget.
    @Parameter(title: "Trade amount (USDT)", default: 500,
               inclusiveRange: (1, 1_000_000))
    var amountUSDT: Int

    @Parameter(title: "Chart window", default: .hour24)
    var window: WindowOption
}
