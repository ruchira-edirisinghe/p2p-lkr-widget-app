import WidgetKit
import P2PKit

struct WidgetCenterReloader: WidgetReloader {
    func reload() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}
