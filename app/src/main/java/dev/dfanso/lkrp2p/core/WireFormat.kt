package dev.dfanso.lkrp2p.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The **only** place that knows Binance's wire format. A schema change upstream
 * is a one-file fix by design.
 */
object WireFormat {
    private const val SUCCESS_CODE = "000000"

    fun decodeAds(body: String): List<Ad> {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw P2PError.Malformed(e.message ?: "not JSON")
        }

        val code = root.optString("code", "")
        if (code != SUCCESS_CODE) {
            throw P2PError.ApiCode(code, root.optNullableString("message"))
        }

        val rows = root.optJSONArray("data") ?: JSONArray()
        if (rows.length() == 0) throw P2PError.EmptyResult

        // A single unparseable ad is skipped, not fatal: the rest of the book
        // is still a usable observation.
        val ads = (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let(::normalise)
        }
        if (ads.isEmpty()) throw P2PError.EmptyResult
        return ads
    }

    /** Numeric fields arrive as strings; rates and counts arrive as numbers. */
    private fun normalise(row: JSONObject): Ad? {
        val adv = row.optJSONObject("adv") ?: return null
        val advertiser = row.optJSONObject("advertiser") ?: JSONObject()

        val price = adv.optNullableString("price")?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val stock = adv.optNullableString("tradableQuantity")?.toDoubleOrNull() ?: return null
        val minFiat = adv.optNullableString("minSingleTransAmount")?.toDoubleOrNull() ?: return null
        // dynamicMaxSingleTransAmount reflects live stock; fall back to the
        // static ceiling if it is ever absent.
        val maxFiat = (adv.optNullableString("dynamicMaxSingleTransAmount")
            ?: adv.optNullableString("maxSingleTransAmount"))?.toDoubleOrNull() ?: return null

        return Ad(
            price = price,
            availableUsdt = stock,
            minFiat = minFiat,
            maxFiat = maxFiat,
            payTimeLimitMinutes = adv.optInt("payTimeLimit", 0),
            advertiserName = advertiser.optNullableString("nickName") ?: "Unknown",
            monthOrderCount = advertiser.optInt("monthOrderCount", 0),
            monthFinishRate = advertiser.optDouble("monthFinishRate", 0.0).nanToZero(),
            positiveRate = advertiser.optDouble("positiveRate", 0.0).nanToZero(),
        )
    }

    /** org.json returns the string "null" for JSON null; treat it as absent. */
    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifEmpty { null }

    private fun Double.nanToZero() = if (isNaN()) 0.0 else this

    /**
     * Mirrors the query parameters of
     * p2p.binance.com/trade/sell/USDT?fiat=LKR&payment=BankSriLanka
     */
    fun requestBody(side: Side, fiat: String, payment: PaymentMethod, rows: Int): String =
        JSONObject().apply {
            put("fiat", fiat)
            put("asset", "USDT")
            put("tradeType", side.wire)
            put("payTypes", JSONArray().put(payment.wire))
            put("page", 1)
            put("rows", rows)
            put("countries", JSONArray())
            put("periods", JSONArray())
            put("proMerchantAds", false)
            put("shieldMerchantAds", false)
            put("filterType", "all")
            put("additionalKycVerifyFilter", 0)
            put("publisherType", JSONObject.NULL)
            put("classifies", JSONArray().put("mass").put("profession").put("fiat_trade"))
        }.toString()
}
