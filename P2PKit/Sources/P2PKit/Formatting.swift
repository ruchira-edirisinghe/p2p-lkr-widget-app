import Foundation

public enum Formatting {
    public static func price(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%.2f", value)
    }

    public static func signedDelta(_ value: Double) -> String {
        value > 0 ? String(format: "+%.2f", value) : String(format: "%.2f", value)
    }

    public static func percent(_ value: Double) -> String {
        value > 0 ? String(format: "+%.2f%%", value) : String(format: "%.2f%%", value)
    }

    public static func usdt(_ amount: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ","
        let number = formatter.string(from: NSNumber(value: amount)) ?? "\(amount)"
        return "\(number) USDT"
    }

    public static func usdt(_ amount: Double) -> String {
        usdt(Int(amount.rounded()))
    }

    public static func fiat(_ amount: Double) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        formatter.groupingSeparator = ","
        return formatter.string(from: NSNumber(value: amount)) ?? "\(Int(amount))"
    }

    public static func relativeAge(_ date: Date, from now: Date = .now) -> String {
        let seconds = Int(now.timeIntervalSince(date))
        switch seconds {
        case ..<60:      return "just now"
        case ..<3_600:   return "\(seconds / 60)m ago"
        case ..<86_400:  return "\(seconds / 3_600)h ago"
        default:         return "\(seconds / 86_400)d ago"
        }
    }
}
