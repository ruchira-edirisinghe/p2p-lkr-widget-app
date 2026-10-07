package dev.dfanso.lkrp2p.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

fun interface AdSource {
    suspend fun fetch(side: Side, fiat: String, payment: PaymentMethod, rows: Int): List<Ad>
}

class BinanceP2PClient : AdSource {
    companion object {
        const val ENDPOINT = "https://p2p.binance.com/bapi/c2c/v2/friendly/c2c/adv/search"

        /** The page sends a browser UA; an obviously scripted one invites blocking. */
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }

    override suspend fun fetch(side: Side, fiat: String, payment: PaymentMethod, rows: Int): List<Ad> =
        withContext(Dispatchers.IO) {
            val body = WireFormat.requestBody(side, fiat, payment, rows).toByteArray()
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                if (status !in 200..299) throw P2PError.HttpStatus(status)
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                WireFormat.decodeAds(text)
            } catch (e: IOException) {
                throw P2PError.Transport(e.message ?: e.javaClass.simpleName)
            } finally {
                connection.disconnect()
            }
        }
}
