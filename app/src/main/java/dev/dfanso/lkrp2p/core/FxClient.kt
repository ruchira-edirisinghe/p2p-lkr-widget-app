package dev.dfanso.lkrp2p.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One day's reference rates: how many units of each currency one US dollar buys. */
data class FxDay(val date: String, val perUsd: Map<String, Double>)

fun interface FxSource {
    /** [date] is "yyyy-MM-dd", or null for the latest published day. */
    suspend fun day(date: String?): FxDay
}

/**
 * Daily reference rates from the open-source currency-api
 * (github.com/fawazahmed0/exchange-api): free, keyless, 300+ currencies
 * including LKR and the major coins, with a dated archive for charts.
 * Served from jsDelivr, with the project's Cloudflare mirror as a fallback.
 */
class FxClient : FxSource {

    override suspend fun day(date: String?): FxDay {
        val tag = date ?: "latest"
        val text = fetchFirst(
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$tag/v1/currencies/usd.min.json",
            "https://$tag.currency-api.pages.dev/v1/currencies/usd.min.json",
        )
        return decodeDay(text)
    }

    /** Currency code to display name, e.g. "lkr" to "Sri Lankan Rupee". */
    suspend fun names(): Map<String, String> {
        val text = fetchFirst(
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies.min.json",
            "https://latest.currency-api.pages.dev/v1/currencies.min.json",
        )
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { json.optString(it) }
    }

    private suspend fun fetchFirst(vararg urls: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (url in urls) {
            try {
                return@withContext get(url)
            } catch (e: Exception) {
                last = e
            }
        }
        throw P2PError.Transport(last?.message ?: "unreachable")
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw P2PError.HttpStatus(status)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw P2PError.Transport(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun decodeDay(text: String): FxDay {
            val json = JSONObject(text)
            val rates = json.optJSONObject("usd") ?: throw P2PError.Malformed("no usd table")
            val perUsd = buildMap {
                for (code in rates.keys()) {
                    val value = rates.optDouble(code)
                    if (value.isFinite() && value > 0) put(code, value)
                }
                put("usd", 1.0)
            }
            return FxDay(json.getString("date"), perUsd)
        }
    }
}
