package dev.dfanso.lkrp2p.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.dfanso.lkrp2p.core.PaymentMethod
import dev.dfanso.lkrp2p.core.Side

/**
 * App-wide preferences. Each widget instance keeps its own side and chart
 * window in Glance state; [side] here is only the default for new widgets and
 * for the in-app screen.
 *
 * [amountUsdt] is the *order size*: an ad only counts if it accepts an order
 * this big. The rate itself is always shown per 1 USDT.
 */
class Settings(private val prefs: SharedPreferences) {
    object Defaults {
        /** Roughly 165,000 LKR, which several ads in the recorded book can fill. */
        const val AMOUNT_USDT = 500
        val SIDE = Side.SELL
        val PAYMENT = PaymentMethod.BANK_SRI_LANKA
        const val FIAT = "LKR"

        /** Android's floor for periodic background work is 15 minutes. */
        const val POLL_MINUTES = 15
        val POLL_CHOICES = listOf(15, 30, 60)
        val ORDER_SIZE_CHOICES = listOf(100, 500, 1_000, 5_000)
        const val RETENTION_DAYS = 30

        /** Three missed polls, and never under 15 minutes, before data reads as stale. */
        fun staleAfterSec(pollMinutes: Int): Long = maxOf(15 * 60L, pollMinutes * 60L * 3)
    }

    var amountUsdt: Int
        get() = prefs.getInt("amountUsdt", 0).takeIf { it > 0 } ?: Defaults.AMOUNT_USDT
        // A non-positive amount cannot be filled by any ad, so refuse it rather
        // than storing a value that guarantees an empty chart.
        set(value) { if (value > 0) prefs.edit { putInt("amountUsdt", value) } }

    var side: Side
        get() = Side.fromWire(prefs.getString("side", null)) ?: Defaults.SIDE
        set(value) = prefs.edit { putString("side", value.wire) }

    var payment: PaymentMethod
        get() = PaymentMethod.fromWire(prefs.getString("payment", null)) ?: Defaults.PAYMENT
        set(value) = prefs.edit { putString("payment", value.wire) }

    val fiat: String get() = Defaults.FIAT

    var pollMinutes: Int
        get() = prefs.getInt("pollMinutes", Defaults.POLL_MINUTES)
            .takeIf { it in Defaults.POLL_CHOICES } ?: Defaults.POLL_MINUTES
        set(value) { if (value in Defaults.POLL_CHOICES) prefs.edit { putInt("pollMinutes", value) } }

    companion object {
        @Volatile private var instance: Settings? = null

        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(
                context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
