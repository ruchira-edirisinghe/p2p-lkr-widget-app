package dev.dfanso.lkrp2p.core

data class Trend(val previous: Double, val current: Double) {
    enum class Direction { UP, DOWN, FLAT }

    val delta: Double get() = current - previous
    val percent: Double get() = if (previous == 0.0) 0.0 else delta / previous * 100
    val direction: Direction
        get() = when {
            delta > 0 -> Direction.UP
            delta < 0 -> Direction.DOWN
            else -> Direction.FLAT
        }
}

object MetricsEngine {

    /**
     * Orders the book best-first for the user: highest price first when selling,
     * lowest first when buying.
     *
     * Binance mostly returns ads in this order already, but a "Promoted Ad" is
     * pinned to the top regardless of price. Seen live on 2026-10-07: a 325.53
     * promoted ad above a sell book starting at 335.58. The sort is stable, so
     * equal prices keep Binance's order.
     */
    fun bestFirst(ads: List<Ad>, side: Side): List<Ad> = when (side) {
        Side.SELL -> ads.sortedByDescending { it.price }
        Side.BUY -> ads.sortedBy { it.price }
    }

    /** The best ad that can actually absorb [amountUsdt]. [ads] must be [bestFirst]-ordered. */
    fun fillable(ads: List<Ad>, amountUsdt: Int): Ad? = ads.firstOrNull { it.canFill(amountUsdt) }

    fun topPrice(ads: List<Ad>): Double? = ads.firstOrNull()?.price

    fun medianTop10(ads: List<Ad>): Double? {
        val prices = ads.take(10).map { it.price }.sorted()
        if (prices.isEmpty()) return null
        val mid = prices.size / 2
        return if (prices.size % 2 == 0) (prices[mid - 1] + prices[mid]) / 2 else prices[mid]
    }

    /**
     * Null only when the book is empty — that is a failed observation. A book
     * with no ad big enough yields a sample whose fillablePrice is null, which
     * is a real finding and must be stored.
     */
    fun makeSample(ads: List<Ad>, side: Side, amountUsdt: Int, timestampSec: Long): Sample? {
        val top = topPrice(ads) ?: return null
        val best = fillable(ads, amountUsdt)
        return Sample(
            timestampSec = timestampSec,
            side = side,
            amountUsdt = amountUsdt,
            fillablePrice = best?.price,
            topPrice = top,
            medianTop10 = medianTop10(ads),
            advertiserName = best?.advertiserName,
            advertiserAvailable = best?.availableUsdt,
            advertiserMinFiat = best?.minFiat,
            advertiserMaxFiat = best?.maxFiat,
        )
    }

    /** Direction across the supplied window; the caller has already scoped it. */
    fun trend(series: List<SeriesPoint>): Trend? {
        if (series.size < 2) return null
        return Trend(previous = series.first().price, current = series.last().price)
    }

    /**
     * Bucket-average a series. [bucketSec] == 0 returns it unchanged.
     *
     * Empty buckets are omitted rather than filled: a gap must stay a gap so the
     * chart draws a break instead of implying a flat rate overnight.
     */
    fun downsample(series: List<SeriesPoint>, bucketSec: Long): List<SeriesPoint> {
        if (bucketSec <= 0) return series
        val sums = LinkedHashMap<Long, DoubleArray>()
        for (point in series) {
            val key = Math.floorDiv(point.timestampSec, bucketSec) * bucketSec
            val entry = sums.getOrPut(key) { DoubleArray(2) }
            entry[0] += point.price
            entry[1] += 1.0
        }
        return sums.map { (key, entry) -> SeriesPoint(key, entry[0] / entry[1]) }
    }
}
