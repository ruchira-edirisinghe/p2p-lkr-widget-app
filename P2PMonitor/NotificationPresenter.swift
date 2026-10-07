import Foundation
import UserNotifications
import P2PKit
import OSLog

/// Posts a macOS notification when a threshold is crossed. The once-per-crossing
/// guarantee lives in AlertEngine; this type only presents.
struct NotificationPresenter: AlertPresenter {
    private let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "notify")

    func requestAuthorization() {
        let logger = self.logger
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound]) { granted, error in
                if let error {
                    logger.warning("authorization failed: \(String(describing: error), privacy: .public)")
                } else {
                    logger.info("notification authorization granted: \(granted, privacy: .public)")
                }
            }
    }

    func present(rule: AlertRule, price: Double) async {
        let content = UNMutableNotificationContent()
        content.title = "USDT/LKR \(rule.side == .sell ? "sell" : "buy") rate"
        let comparison = rule.direction == .above ? "rose above" : "fell below"
        content.body = String(
            format: "%@ %.2f — now %.2f for %d USDT",
            comparison, rule.threshold, price, rule.amountUSDT)
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "\(rule.id)-\(Int(Date.now.timeIntervalSince1970))",
            content: content, trigger: nil)
        do {
            try await UNUserNotificationCenter.current().add(request)
        } catch {
            logger.warning("post failed: \(String(describing: error), privacy: .public)")
        }
    }
}
