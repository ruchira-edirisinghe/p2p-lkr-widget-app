package dev.dfanso.lkrp2p.core

/**
 * The user's action, not the advertiser's. Requesting [SELL] returns ads whose
 * own `adv.tradeType` reads "BUY" — the API states the counterparty's side, so
 * the two are always inverted.
 */
enum class Side(val wire: String, val label: String) {
    SELL("SELL", "Sell"),
    BUY("BUY", "Buy");

    val opposite: Side get() = if (this == SELL) BUY else SELL

    companion object {
        fun fromWire(value: String?): Side? = entries.firstOrNull { it.wire == value }
    }
}

enum class PaymentMethod(val wire: String, val displayName: String) {
    BANK_SRI_LANKA("BankSriLanka", "Bank Transfer (Sri Lanka)"),
    BANK_TRANSFER("BANK", "Bank Transfer");

    companion object {
        fun fromWire(value: String?): PaymentMethod? = entries.firstOrNull { it.wire == value }
    }
}

/** One advertisement, normalised. Fiat limits are in LKR, never in USDT. */
data class Ad(
    val price: Double,
    val availableUsdt: Double,
    val minFiat: Double,
    val maxFiat: Double,
    val payTimeLimitMinutes: Int,
    val advertiserName: String,
    val monthOrderCount: Int,
    val monthFinishRate: Double,
    val positiveRate: Double,
) {
    /** Whether this ad can absorb [amountUsdt] in a single order. */
    fun canFill(amountUsdt: Int): Boolean = canFill(amountUsdt.toDouble())

    fun canFill(amountUsdt: Double): Boolean {
        if (availableUsdt < amountUsdt) return false
        val fiat = amountUsdt * price
        return fiat >= minFiat && fiat <= maxFiat
    }

    /** Whether this ad can take an order worth [fiat] LKR. */
    fun canFillFiat(fiat: Double): Boolean =
        fiat >= minFiat && fiat <= maxFiat && availableUsdt >= fiat / price
}

/**
 * One observation. [fillablePrice] is null when no ad could fill the amount — a
 * real, recordable finding, distinct from a failed poll, which stores nothing.
 */
data class Sample(
    val timestampSec: Long,
    val side: Side,
    val amountUsdt: Int,
    val fillablePrice: Double?,
    val topPrice: Double,
    val medianTop10: Double?,
    val advertiserName: String?,
    val advertiserAvailable: Double?,
    val advertiserMinFiat: Double?,
    val advertiserMaxFiat: Double?,
)

data class SeriesPoint(val timestampSec: Long, val price: Double)

/**
 * The widget's segmented control. Retention is 30 days, so "Month" is the
 * longest window there is data for.
 */
enum class ChartWindow(val key: String, val label: String, val durationSec: Long, val bucketSec: Long) {
    DAY("1d", "Day", 86_400, 0),
    WEEK("7d", "Week", 604_800, 3_600),
    MONTH("30d", "Month", 2_592_000, 21_600);

    companion object {
        fun fromKey(value: String?): ChartWindow? = entries.firstOrNull { it.key == value }
    }
}

sealed class P2PError(message: String) : Exception(message) {
    class Transport(detail: String) : P2PError("Network error: $detail")
    class HttpStatus(val code: Int) : P2PError("HTTP $code")
    class ApiCode(val code: String, detail: String?) : P2PError("API error $code ${detail.orEmpty()}")
    object EmptyResult : P2PError("No ads returned") {
        private fun readResolve(): Any = EmptyResult
    }
    class Malformed(detail: String) : P2PError("Malformed response: $detail")
}
