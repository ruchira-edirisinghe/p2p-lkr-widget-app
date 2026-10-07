package dev.dfanso.lkrp2p.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Formatting {
    // Fixed locale: the rate is always shown with "." decimals and "," grouping,
    // the way Binance and Sri Lankan banks display it, whatever the phone locale.
    private val symbols = DecimalFormatSymbols(Locale.US)
    private val priceFormat = DecimalFormat("#,##0.00", symbols)
    private val wholeFormat = DecimalFormat("#,##0", symbols)

    fun price(value: Double?): String = value?.let { priceFormat.format(it) } ?: "—"

    fun whole(value: Double): String = wholeFormat.format(value)

    fun whole(value: Int): String = wholeFormat.format(value)

    fun signedDelta(value: Double): String =
        if (value > 0) "+${priceFormat.format(value)}" else priceFormat.format(value)

    fun percent(value: Double): String =
        String.format(Locale.US, if (value > 0) "+%.2f%%" else "%.2f%%", value)

    fun usdt(amount: Double): String = "${wholeFormat.format(amount)} USDT"

    fun relativeAge(timestampSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val seconds = nowSec - timestampSec
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }
}
