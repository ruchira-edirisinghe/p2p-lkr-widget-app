import WidgetKit
import Foundation
import P2PKit

/// Reads only. All fetching happens in the container app: widget timeline
/// reloads are budget-capped by the OS and cannot hold a five-minute cadence.
struct RateTimelineProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> RateEntry { .placeholder() }

    func snapshot(for configuration: RateConfigurationIntent,
                  in context: Context) async -> RateEntry {
        entry(for: configuration, now: .now)
    }

    func timeline(for configuration: RateConfigurationIntent,
                  in context: Context) async -> Timeline<RateEntry> {
        let now = Date.now
        // The app reloads timelines after each successful poll; this policy is
        // only a backstop for when the collector is not running.
        return Timeline(entries: [entry(for: configuration, now: now)],
                        policy: .after(now.addingTimeInterval(600)))
    }

    private func entry(for configuration: RateConfigurationIntent, now: Date) -> RateEntry {
        guard let url = AppGroup.databaseURL, let store = try? Store(fileURL: url) else {
            return RateEntry(date: now, side: configuration.side.asSide,
                             amountUSDT: configuration.amountUSDT,
                             window: configuration.window.asWindow, sample: nil, series: [],
                             trend: nil, state: .error, topAds: [])
        }

        let side = configuration.side.asSide
        let amount = configuration.amountUSDT
        let window = configuration.window.asWindow

        guard let sample = try? store.latest(side: side, amountUSDT: amount) else {
            return RateEntry(date: now, side: side, amountUSDT: amount, window: window,
                             sample: nil, series: [], trend: nil, state: .noData, topAds: [])
        }

        let series = (try? store.series(side: side, amountUSDT: amount,
                                        window: window, now: now)) ?? []
        let ads = (try? store.snapshot(side: side)).flatMap { $0 }?.ads ?? []

        let age = now.timeIntervalSince(sample.timestamp)
        let state: RateEntry.State = age > Settings.Defaults.stalenessThreshold
            ? .stale(since: sample.timestamp)
            : .ok

        return RateEntry(date: now, side: side, amountUSDT: amount, window: window,
                         sample: sample, series: series,
                         trend: MetricsEngine.trend(series: series), state: state,
                         topAds: Array(ads.prefix(5)))
    }
}
