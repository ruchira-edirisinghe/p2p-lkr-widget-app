package dev.dfanso.lkrp2p.core

/** Prices an arbitrary amount against the current order book, either direction. */
object Converter {

    enum class Basis {
        /** A real ad can take exactly this order. */
        EXACT,

        /** Below every ad's minimum: priced at the headline (order-size) rate. */
        TOO_SMALL,

        /** Above every ad's limit or stock: priced at the headline rate. */
        TOO_LARGE,
    }

    data class Quote(val usdt: Double, val lkr: Double, val rate: Double, val ad: Ad?, val basis: Basis)

    /**
     * [ads] must be best-first for the side. [headline] is the tracked rate,
     * used when no single ad can take the order, so 1 USDT still converts.
     */
    fun quote(ads: List<Ad>, headline: Double?, amount: Double, amountIsUsdt: Boolean): Quote? {
        if (amount <= 0) return null
        val ad = if (amountIsUsdt) ads.firstOrNull { it.canFill(amount) } else ads.firstOrNull { it.canFillFiat(amount) }
        val rate = ad?.price ?: headline ?: return null
        val usdt = if (amountIsUsdt) amount else amount / rate
        val lkr = if (amountIsUsdt) amount * rate else amount
        val basis = when {
            ad != null -> Basis.EXACT
            ads.isNotEmpty() && lkr < ads.minOf { it.minFiat } -> Basis.TOO_SMALL
            ads.isEmpty() -> Basis.TOO_SMALL
            else -> Basis.TOO_LARGE
        }
        return Quote(usdt, lkr, rate, ad, basis)
    }
}
