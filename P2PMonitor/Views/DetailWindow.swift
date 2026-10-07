import SwiftUI
import Charts
import P2PKit

struct DetailWindow: View {
    @State private var model: DetailViewModel

    init(model: DetailViewModel) {
        _model = State(initialValue: model)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            chart.frame(minHeight: 180)
            Divider()
            AdTable(ads: model.ads, amountUSDT: model.amountUSDT)
                .frame(minHeight: 200)
            footer
        }
        .padding(16)
        .frame(minWidth: 720, minHeight: 560)
        .task { model.reload() }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Formatting.price(model.latest?.fillablePrice))
                    .font(.system(size: 34, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Text("best fillable · \(Formatting.usdt(model.amountUSDT))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let trend = model.trend {
                let up = trend.direction == .up
                let flat = trend.direction == .flat
                HStack(spacing: 4) {
                    Image(systemName: flat ? "minus" : (up ? "arrow.up.right" : "arrow.down.right"))
                    Text("\(Formatting.signedDelta(trend.delta)) (\(Formatting.percent(trend.percent)))")
                        .monospacedDigit()
                }
                .font(.callout.weight(.medium))
                .foregroundStyle(flat ? Color.secondary : (up ? Color.green : Color.red))
            }

            Spacer()

            Picker("", selection: $model.window) {
                ForEach(ChartWindow.allCases, id: \.self) { window in
                    Text(window.rawValue).tag(window)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(width: 220)
        }
    }

    private var chart: some View {
        Group {
            if model.series.count < 2 {
                ContentUnavailableView(
                    "Collecting history",
                    systemImage: "chart.xyaxis.line",
                    description: Text("Samples arrive every five minutes. The chart fills in as they accumulate."))
            } else {
                Chart {
                    ForEach(model.series, id: \.timestamp) { point in
                        LineMark(x: .value("Time", point.timestamp),
                                 y: .value("Price", point.price))
                        .interpolationMethod(.monotone)
                        AreaMark(x: .value("Time", point.timestamp),
                                 y: .value("Price", point.price))
                        .interpolationMethod(.monotone)
                        .foregroundStyle(.linearGradient(
                            colors: [.accentColor.opacity(0.28), .accentColor.opacity(0.02)],
                            startPoint: .top, endPoint: .bottom))
                    }
                }
                .chartYScale(domain: .automatic(includesZero: false))
            }
        }
    }

    private var footer: some View {
        HStack(spacing: 12) {
            if let captured = model.capturedAt {
                Label("book \(Formatting.relativeAge(captured))", systemImage: "clock")
            }
            if let top = model.latest?.topPrice {
                Label("top of book \(Formatting.price(top))", systemImage: "arrow.up.to.line")
            }
            Spacer()
            Button {
                Task { await model.refreshNow() }
            } label: {
                if model.isRefreshing {
                    ProgressView().controlSize(.small)
                } else {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
            }
            .disabled(model.isRefreshing)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }
}
