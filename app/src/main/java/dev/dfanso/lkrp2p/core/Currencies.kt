package dev.dfanso.lkrp2p.core

import java.util.Currency

/** Display helpers for currency codes from the reference-rate source. */
object Currencies {
    private val iso: Set<String> by lazy {
        runCatching { Currency.getAvailableCurrencies().map { it.currencyCode.lowercase() }.toSet() }.getOrDefault(emptySet())
    }

    /** True for ISO 4217 money; false for coins and tokens. */
    fun isFiat(code: String) = code.lowercase() in iso

    /**
     * The flag of the issuing country, built from the first two letters of the
     * ISO code (LKR → 🇱🇰, EUR → 🇪🇺). Coins and the X-codes (gold, SDR) have none.
     */
    fun flag(code: String): String? {
        val c = code.uppercase()
        if (!isFiat(c) || c.startsWith("X") || c.length != 3) return null
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    /** A flag, or for coins a short mark to stand in for one. */
    fun badge(code: String): String = flag(code) ?: when (code.lowercase()) {
        "usdt" -> "₮"
        "btc" -> "₿"
        "eth" -> "Ξ"
        else -> "◎"
    }
}
