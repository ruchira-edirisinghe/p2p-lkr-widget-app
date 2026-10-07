import SwiftUI
import ServiceManagement
import OSLog
import P2PKit

struct SettingsView: View {
    private let settings = AppEnvironment.shared.settings
    private let store = AppEnvironment.shared.store
    private let logger = Logger(subsystem: "dev.dfanso.p2pmonitor", category: "settings")

    @State private var amountText: String = ""
    @State private var side: Side = .sell
    @State private var payment: PaymentMethod = .bankSriLanka
    @State private var launchAtLogin = true

    var body: some View {
        Form {
            Section("Rate") {
                Picker("Side", selection: $side) {
                    Text("Sell USDT").tag(Side.sell)
                    Text("Buy USDT").tag(Side.buy)
                }
                .onChange(of: side) { _, newValue in settings.side = newValue }

                TextField("Trade amount (USDT)", text: $amountText)
                    .monospacedDigit()
                    .onSubmit(commitAmount)

                Text("Only ads whose stock and per-order limits can absorb this amount are considered. Raising it usually lowers the quoted rate.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Picker("Payment", selection: $payment) {
                    ForEach(PaymentMethod.allCases, id: \.self) { method in
                        Text(method.displayName).tag(method)
                    }
                }
                .onChange(of: payment) { _, newValue in settings.payment = newValue }
            }

            Section("Alerts") {
                AlertRulesEditor(store: store, settings: settings)
            }

            Section("General") {
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, isOn in
                        settings.launchAtLogin = isOn
                        updateLoginItem(enabled: isOn)
                    }
                Text("The app has no Dock or menu bar icon. It collects a sample every five minutes while running; quitting it stops collection and the widget will show a stale state.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .onAppear {
            amountText = String(settings.amountUSDT)
            side = settings.side
            payment = settings.payment
            launchAtLogin = settings.launchAtLogin
        }
    }

    private func commitAmount() {
        guard let value = Int(amountText), value > 0 else {
            amountText = String(settings.amountUSDT)
            return
        }
        settings.amountUSDT = value
    }

    private func updateLoginItem(enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            logger.warning("login item update failed: \(String(describing: error), privacy: .public)")
        }
    }
}
