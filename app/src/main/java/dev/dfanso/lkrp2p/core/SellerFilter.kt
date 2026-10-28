package dev.dfanso.lkrp2p.core

/**
 * The user's bar for a seller to count towards the rate. A 3-order newcomer
 * at the top of the book quotes a price few people would trust; requiring a
 * track record makes the rate one you could actually get.
 */
data class SellerFilter(val minFinishPercent: Int = 0, val minMonthOrders: Int = 0) {
    val isActive: Boolean get() = minFinishPercent > 0 || minMonthOrders > 0

    // monthFinishRate arrives as a fraction; the epsilon keeps 0.95 * 100 at 95.
    fun accepts(ad: Ad): Boolean =
        ad.monthFinishRate * 100 + 1e-6 >= minFinishPercent && ad.monthOrderCount >= minMonthOrders

    fun apply(ads: List<Ad>): List<Ad> = if (isActive) ads.filter(::accepts) else ads
}
