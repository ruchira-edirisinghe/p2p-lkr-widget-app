package dev.dfanso.lkrp2p.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.dfanso.lkrp2p.core.PaymentMethod
import dev.dfanso.lkrp2p.core.SellerFilter
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.render.ColorTheme

/**
 * App-wide preferences. The app tracks one market only: selling USDT to
 * sellers who pay by Sri Lankan bank transfer, so [side] and [payment] are
 * fixed. Each widget instance keeps its own chart window in Glance state.
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
        val OPACITY_CHOICES = listOf(100, 85, 70, 50, 30, 0)
        val ORDER_SIZE_CHOICES = listOf(100, 500, 1_000, 5_000)
        val FINISH_RATE_CHOICES = listOf(0, 90, 95, 98)
        val MONTH_ORDER_CHOICES = listOf(0, 50, 100, 500)
        const val RETENTION_DAYS = 30

        /** Three missed polls, and never under 15 minutes, before data reads as stale. */
        fun staleAfterSec(pollMinutes: Int): Long = maxOf(15 * 60L, pollMinutes * 60L * 3)
    }

    var amountUsdt: Int
        get() = prefs.getInt("amountUsdt", 0).takeIf { it > 0 } ?: Defaults.AMOUNT_USDT
        // A non-positive amount cannot be filled by any ad, so refuse it rather
        // than storing a value that guarantees an empty chart.
        set(value) { if (value > 0) prefs.edit { putInt("amountUsdt", value) } }

    val side: Side get() = Defaults.SIDE

    val payment: PaymentMethod get() = Defaults.PAYMENT

    val fiat: String get() = Defaults.FIAT

    /** Sellers below this bar don't count towards the rate. Off by default. */
    var sellerFilter: SellerFilter
        get() = SellerFilter(
            prefs.getInt("minFinishPercent", 0).takeIf { it in Defaults.FINISH_RATE_CHOICES } ?: 0,
            prefs.getInt("minMonthOrders", 0).takeIf { it in Defaults.MONTH_ORDER_CHOICES } ?: 0,
        )
        set(value) = prefs.edit {
            putInt("minFinishPercent", value.minFinishPercent)
            putInt("minMonthOrders", value.minMonthOrders)
        }

    var pollMinutes: Int
        get() = prefs.getInt("pollMinutes", Defaults.POLL_MINUTES)
            .takeIf { it in Defaults.POLL_CHOICES } ?: Defaults.POLL_MINUTES
        set(value) { if (value in Defaults.POLL_CHOICES) prefs.edit { putInt("pollMinutes", value) } }

    /** Keep the current rate in the notification bar. */
    var statusNotification: Boolean
        get() = prefs.getBoolean("statusNotification", false)
        set(value) = prefs.edit { putBoolean("statusNotification", value) }

    /** Send one summary of the last 24 hours each morning. */
    var dailySummary: Boolean
        get() = prefs.getBoolean("dailySummary", false)
        set(value) = prefs.edit { putBoolean("dailySummary", value) }

    /** The local date ("2026-10-28") the last daily summary went out. */
    var lastSummaryDay: String?
        get() = prefs.getString("lastSummaryDay", null)
        set(value) = prefs.edit { putString("lastSummaryDay", value) }

    /** The app's colours, and the default for widgets that follow the app. */
    var appTheme: ColorTheme
        get() = ColorTheme.fromKey(prefs.getString("appTheme", null)) ?: ColorTheme.DEFAULT
        set(value) = prefs.edit { putString("appTheme", value.key) }

    /** Widgets' theme when they have none of their own; null follows [appTheme]. */
    var widgetTheme: ColorTheme?
        get() = ColorTheme.fromKey(prefs.getString("widgetTheme", null))
        set(value) = prefs.edit { putString("widgetTheme", value?.key) }

    /** Widget background opacity, percent. */
    var widgetOpacity: Int
        get() = prefs.getInt("widgetOpacity", 100).takeIf { it in Defaults.OPACITY_CHOICES } ?: 100
        set(value) { if (value in Defaults.OPACITY_CHOICES) prefs.edit { putInt("widgetOpacity", value) } }

    /** The theme a widget without its own choice is drawn in. */
    val effectiveWidgetTheme: ColorTheme get() = widgetTheme ?: appTheme

    /** The currency converter's pair, also the default for new currency widgets. */
    var fxFrom: String
        get() = prefs.getString("fxFrom", null) ?: "usd"
        set(value) = prefs.edit { putString("fxFrom", value.lowercase()) }

    var fxTo: String
        get() = prefs.getString("fxTo", null) ?: "lkr"
        set(value) = prefs.edit { putString("fxTo", value.lowercase()) }

    /** Pairs pinned to the top of the Currencies tab, as "usd/lkr". */
    var fxFavorites: List<String>
        get() = prefs.getString("fxFavorites", null)?.split(",")?.filter { "/" in it }
            ?: listOf("usd/lkr", "eur/lkr", "gbp/lkr", "aed/lkr", "inr/lkr")
        set(value) = prefs.edit { putString("fxFavorites", value.distinct().joinToString(",")) }

    companion object {
        @Volatile private var instance: Settings? = null

        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(
                context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
