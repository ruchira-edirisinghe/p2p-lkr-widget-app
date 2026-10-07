import SwiftUI
import P2PKit

struct AlertRulesEditor: View {
    let store: Store?
    // Qualified: bare `Settings` is ambiguous between SwiftUI.Settings
    // (the scene) and P2PKit.Settings (this type) in any file importing both.
    let settings: P2PKit.Settings

    @State private var rules: [AlertRule] = []
    @State private var threshold: String = ""
    @State private var direction: ThresholdDirection = .above

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if rules.isEmpty {
                Text("No alerts. Add one to be notified when the rate crosses a level.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(rules) { rule in
                    HStack {
                        Image(systemName: rule.direction == .above
                              ? "arrow.up.right.circle" : "arrow.down.right.circle")
                        Text("\(rule.side == .sell ? "Sell" : "Buy") \(Formatting.usdt(rule.amountUSDT)) \(rule.direction == .above ? "above" : "below") \(Formatting.price(rule.threshold))")
                            .font(.callout)
                        Spacer()
                        Button {
                            try? store?.deleteAlert(id: rule.id)
                            load()
                        } label: {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }

            HStack(spacing: 8) {
                Picker("", selection: $direction) {
                    Text("Above").tag(ThresholdDirection.above)
                    Text("Below").tag(ThresholdDirection.below)
                }
                .labelsHidden()
                .frame(width: 110)

                TextField("Threshold", text: $threshold)
                    .frame(width: 100)
                    .monospacedDigit()

                Button("Add") { add() }
                    .disabled(Double(threshold) == nil)
            }
        }
        .onAppear(perform: load)
    }

    private func load() {
        rules = (try? store?.alertRules()).flatMap { $0 } ?? []
    }

    private func add() {
        guard let value = Double(threshold), let store else { return }
        let rule = AlertRule(side: settings.side, amountUSDT: settings.amountUSDT,
                             threshold: value, direction: direction)
        // Start armed so the first crossing after creation notifies.
        try? store.upsertAlert(rule, state: .armed, firedAt: nil)
        threshold = ""
        load()
    }
}
