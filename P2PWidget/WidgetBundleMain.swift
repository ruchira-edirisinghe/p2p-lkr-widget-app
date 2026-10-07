import WidgetKit
import SwiftUI

struct RateWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "RateWidget",
                               intent: RateConfigurationIntent.self,
                               provider: RateTimelineProvider()) { entry in
            RateWidgetView(entry: entry)
        }
        .configurationDisplayName("USDT/LKR Rate")
        .description("Best Binance P2P rate that can fill your trade size.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct P2PWidgetBundle: WidgetBundle {
    var body: some Widget { RateWidget() }
}
