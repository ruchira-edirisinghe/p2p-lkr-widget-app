package dev.dfanso.lkrp2p.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the macOS P2PKit suite, against the same recorded responses. */
class CoreTest {
    private fun fixture(name: String): List<Ad> =
        WireFormat.decodeAds(javaClass.classLoader!!.getResource(name)!!.readText())

    private val sell get() = fixture("lkr-sell-20260907.json")
    private val buy get() = fixture("lkr-buy-20260907.json")

    @Test fun sellAt500SkipsTheUntradeableTopOfBook() {
        val best = MetricsEngine.fillable(sell, 500)!!
        // Top of book is 332.00 but demands a 499,999 LKR minimum (~1,500 USDT).
        assertEquals(331.00, best.price, 0.0)
        assertEquals("TD_TrustPay_LK", best.advertiserName)
    }

    @Test fun rejectsSamePricedAdThatLacksStock() {
        // Two ads quote 331.00; the earlier one holds only 100 USDT.
        assertEquals(700.00, MetricsEngine.fillable(sell, 500)!!.availableUsdt, 0.0)
    }

    @Test fun sellAtLargerAmountPicksADeeperAd() {
        val best = MetricsEngine.fillable(sell, 2000)!!
        assertEquals(330.67, best.price, 0.0)
        assertEquals("HASSY-THECRYPTOQUEEN", best.advertiserName)
    }

    @Test fun buySideUsesTheSameFirstMatchRule() {
        val best = MetricsEngine.fillable(buy, 500)!!
        assertEquals(331.49, best.price, 0.0)
        assertEquals("Alilruben", best.advertiserName)
    }

    @Test fun promotedAdPinnedAboveTheBookDoesNotWin() {
        fun ad(price: Double) = Ad(price, 10_000.0, 10_000.0, 5_000_000.0, 15, "x", 1, 1.0, 1.0)
        // Live order on 2026-10-07: a promoted 325.53 ad above a book starting at 335.58.
        val book = listOf(ad(325.53), ad(335.58), ad(335.55))
        assertEquals(335.58, MetricsEngine.fillable(MetricsEngine.bestFirst(book, Side.SELL), 500)!!.price, 0.0)
        assertEquals(325.53, MetricsEngine.fillable(MetricsEngine.bestFirst(book, Side.BUY), 500)!!.price, 0.0)
    }

    @Test fun fixturesAreAlreadyBestFirst() {
        assertEquals(sell, MetricsEngine.bestFirst(sell, Side.SELL))
        assertEquals(buy, MetricsEngine.bestFirst(buy, Side.BUY))
    }

    @Test fun converterPricesOneUsdtAtTheHeadlineRate() {
        val book = MetricsEngine.bestFirst(sell, Side.SELL)
        // Ads have LKR minimums far above 1 USDT, so the tracked rate is used.
        assertTrue(book.none { it.canFill(1.0) })
        val one = Converter.quote(book, 331.00, 1.0, amountIsUsdt = true)!!
        assertEquals(331.00, one.lkr, 0.0)
        assertEquals(Converter.Basis.TOO_SMALL, one.basis)
    }

    @Test fun converterUsesTheBestAdThatFitsTheAmount() {
        val book = MetricsEngine.bestFirst(sell, Side.SELL)
        val q = Converter.quote(book, 331.00, 2000.0, amountIsUsdt = true)!!
        assertEquals(Converter.Basis.EXACT, q.basis)
        val best = book.first { it.canFill(2000.0) }.price
        assertEquals(best, q.rate, 0.0)
        assertEquals(2000 * best, q.lkr, 0.001)
    }

    @Test fun converterWorksFromLkr() {
        val book = MetricsEngine.bestFirst(sell, Side.SELL)
        val q = Converter.quote(book, 331.00, 165_500.0, amountIsUsdt = false)!!
        assertEquals(Converter.Basis.EXACT, q.basis)
        assertEquals(165_500.0 / q.rate, q.usdt, 0.0001)
        assertTrue(q.ad!!.canFillFiat(165_500.0))
    }

    @Test fun converterFlagsOrdersNoAdCanTake() {
        val book = MetricsEngine.bestFirst(sell, Side.SELL)
        assertEquals(Converter.Basis.TOO_LARGE, Converter.quote(book, 331.0, 10_000_000.0, true)!!.basis)
        assertNull(Converter.quote(book, null, 0.0, true))
    }

    @Test fun returnsNullWhenNoAdCanFill() {
        assertNull(MetricsEngine.fillable(sell, 10_000_000))
    }

    @Test fun topPriceAndMedian() {
        assertEquals(332.00, MetricsEngine.topPrice(sell)!!, 0.0)
        assertEquals(330.765, MetricsEngine.medianTop10(sell)!!, 0.0001)
    }

    @Test fun sampleWithNoFillableAdIsStillASample() {
        val sample = MetricsEngine.makeSample(sell, Side.SELL, 10_000_000, 1_000)!!
        assertNull(sample.fillablePrice)
        assertEquals(332.00, sample.topPrice, 0.0)
        assertNull(MetricsEngine.makeSample(emptyList(), Side.SELL, 500, 1_000))
    }

    @Test(expected = P2PError.ApiCode::class)
    fun nonSuccessCodeIsAnApiError() {
        WireFormat.decodeAds("""{"code":"100001","message":"busy","data":null}""")
    }

    @Test(expected = P2PError.EmptyResult::class)
    fun emptyDataIsEmptyResult() {
        WireFormat.decodeAds("""{"code":"000000","data":[]}""")
    }

    @Test(expected = P2PError.Malformed::class)
    fun garbageIsMalformed() {
        WireFormat.decodeAds("<html>blocked</html>")
    }

    @Test fun requestBodyMirrorsThePageQuery() {
        val body = org.json.JSONObject(WireFormat.requestBody(Side.SELL, "LKR", PaymentMethod.BANK_SRI_LANKA, 20))
        assertEquals("SELL", body.getString("tradeType"))
        assertEquals("BankSriLanka", body.getJSONArray("payTypes").getString(0))
        assertTrue(body.isNull("publisherType"))
    }

    @Test fun alertsAreEdgeTriggered() {
        val rule = AlertRule(side = Side.SELL, amountUsdt = 500, threshold = 335.0, direction = ThresholdDirection.ABOVE)
        var state = AlertState.ARMED
        val fired = listOf(334.0, 335.5, 336.0, 334.0, 335.0).map { price ->
            AlertEngine.evaluate(rule, price, state).also { state = it.newState }.fire
        }
        assertEquals(listOf(false, true, false, false, true), fired)
    }

    @Test fun downsampleAveragesBucketsAndKeepsGaps() {
        val series = listOf(
            SeriesPoint(0, 330.0), SeriesPoint(1_800, 332.0),
            // Hour 1 is empty and must stay empty.
            SeriesPoint(7_200, 331.0),
        )
        val out = MetricsEngine.downsample(series, 3_600)
        assertEquals(listOf(SeriesPoint(0, 331.0), SeriesPoint(7_200, 331.0)), out)
        assertEquals(series, MetricsEngine.downsample(series, 0))
    }

    @Test fun trendNeedsTwoPoints() {
        assertNull(MetricsEngine.trend(listOf(SeriesPoint(0, 1.0))))
        val trend = MetricsEngine.trend(listOf(SeriesPoint(0, 330.0), SeriesPoint(1, 333.3)))!!
        assertEquals(Trend.Direction.UP, trend.direction)
        assertEquals(1.0, trend.percent, 0.0001)
    }

    @Test fun formattingIsLocaleIndependent() {
        assertEquals("165,350", Formatting.whole(165_350.4))
        assertEquals("330.70", Formatting.price(330.7))
        assertEquals("+0.42%", Formatting.percent(0.4234))
        assertFalse(Formatting.price(null).any(Char::isDigit))
    }
}
