package dev.dfanso.lkrp2p

import dev.dfanso.lkrp2p.core.Sample
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.data.Store
import kotlin.math.sin

/**
 * Shared history for the render tests, so widget and app screenshots tell the
 * same story. The newest sell rate is 331.00 and the buy rate 331.49: the
 * fillable 500 USDT prices in the recorded order books under test/resources.
 */
object RenderSeed {
    const val ORDER_SIZE = 500
    const val BUY_PREMIUM = 0.49

    fun seed(store: Store, days: Int = 30, endsSecAgo: Long = 0) {
        // Store is a process-wide singleton; other render tests seed it too.
        store.clearForTests()
        val now = System.currentTimeMillis() / 1000 - endsSecAgo
        for (i in 0 until 4 * 24 * days) {
            val ts = now - i * 900L
            // i counts back in time: a slow swell plus intraday wobble, ending on a rise.
            val price = 331.0 - 1.1 * sin(i / 55.0) + 0.35 * sin(i / 6.0) - i * 0.0008
            for (side in Side.entries) {
                val p = if (side == Side.SELL) price else price + BUY_PREMIUM
                store.append(Sample(ts, side, ORDER_SIZE, p, p + 1, p + 0.2, "Trader", 700.0, 10_000.0, 300_000.0))
            }
        }
    }
}
