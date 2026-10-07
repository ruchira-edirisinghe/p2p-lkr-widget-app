import SwiftUI
import WidgetKit

struct RateWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: RateEntry

    var body: some View {
        content.containerBackground(.fill.tertiary, for: .widget)
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .systemSmall:  SmallRateView(entry: entry)
        case .systemLarge:  LargeRateView(entry: entry)
        default:            MediumRateView(entry: entry)
        }
    }
}
