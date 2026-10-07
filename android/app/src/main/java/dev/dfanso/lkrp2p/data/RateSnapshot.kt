package dev.dfanso.lkrp2p.data

import android.content.Context
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.MetricsEngine
import dev.dfanso.lkrp2p.core.Sample
import dev.dfanso.lkrp2p.core.SeriesPoint
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.Trend

/** Everything the widget and the home screen need to draw one side/window. */
data class RateSnapshot(
    val side: Side,
    val window: ChartWindow,
    val amountUsdt: Int,
    val sample: Sample?,
    val series: List<SeriesPoint>,
    val trend: Trend?,
    val nowSec: Long,
    val gapSec: Long,
    val staleAfterSec: Long,
) {
    /** LKR per 1 USDT, from the best ad that accepts an order of [amountUsdt]. */
    val price: Double? get() = sample?.fillablePrice
    val isStale: Boolean
        get() = sample == null || nowSec - sample.timestampSec > staleAfterSec

    companion object {
        fun load(context: Context, side: Side, window: ChartWindow): RateSnapshot {
            val settings = Settings.get(context)
            val store = Store.get(context)
            val amount = settings.amountUsdt
            val now = System.currentTimeMillis() / 1000
            val series = store.series(side, amount, window, now)
            return RateSnapshot(
                side = side,
                window = window,
                amountUsdt = amount,
                sample = store.latest(side, amount),
                series = series,
                trend = MetricsEngine.trend(series),
                nowSec = now,
                // Four missed polls (at least 30 min, or 2.5 buckets) before the line breaks.
                gapSec = maxOf(settings.pollMinutes * 60L * 4, 30 * 60L, window.bucketSec * 5 / 2),
                staleAfterSec = Settings.Defaults.staleAfterSec(settings.pollMinutes),
            )
        }
    }
}
