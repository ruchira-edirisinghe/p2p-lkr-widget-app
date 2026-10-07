package dev.dfanso.lkrp2p.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.Ad
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.MetricsEngine
import dev.dfanso.lkrp2p.core.Side

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdsScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    // Which amount to judge ads against; starts at the order size used for the rate.
    var amount by rememberSaveable(state.orderSize) { mutableStateOf(state.orderSize) }
    var showUnfillable by rememberSaveable { mutableStateOf(false) }
    val ads = state.book?.ads.orEmpty()
    val fillable = ads.filter { it.canFill(amount) }
    val unfillable = ads.filterNot { it.canFill(amount) }
    val best = MetricsEngine.fillable(ads, amount)

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Ads", color = Ui.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Segmented(
                        Side.entries, state.side,
                        { if (it == Side.SELL) "Sell USDT" else "Buy USDT" },
                        vm::setSide,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Order size")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(100, 500, 1_000, 5_000).forEach { size ->
                                val picked = size == amount
                                Text(
                                    Formatting.whole(size),
                                    color = if (picked) Ui.Bg else Ui.Text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .background(if (picked) Ui.Up else Ui.Raised, RoundedCornerShape(50))
                                        .clickable { amount = size }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    Text(
                        "${fillable.size} of ${ads.size} ads can take ${Formatting.usdt(amount.toDouble())} " +
                            "(${Formatting.whole(amount * (best?.price ?: 0.0))} LKR) in one order." +
                            book(state)?.let { " Updated $it." }.orEmpty(),
                        color = Ui.Dim, fontSize = 13.sp,
                    )
                    OutlinedButton(
                        onClick = {
                            val path = if (state.side == Side.SELL) "sell" else "buy"
                            val url = "https://p2p.binance.com/trade/$path/USDT?fiat=LKR&payment=${state.payment.wire}"
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(painterResource(R.drawable.ic_open), null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open Binance P2P to trade")
                    }
                }
            }

            if (ads.isEmpty()) {
                item { Text("No ads loaded yet. Pull down to refresh.", color = Ui.Dim, fontSize = 14.sp) }
            }
            items(fillable) { ad -> AdCard(ad, isBest = ad === best, fits = true, amount = amount) }

            if (unfillable.isNotEmpty()) {
                item {
                    Text(
                        (if (showUnfillable) "Hide " else "Show ") +
                            "${unfillable.size} ads that can't take this order",
                        color = Ui.Up, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showUnfillable = !showUnfillable }
                            .padding(vertical = 12.dp),
                    )
                }
                if (showUnfillable) items(unfillable) { ad -> AdCard(ad, isBest = false, fits = false, amount = amount) }
            }
        }
    }
}

private fun book(state: UiState): String? = state.book?.let { Formatting.relativeAge(it.capturedAtSec) }

@Composable
private fun AdCard(ad: Ad, isBest: Boolean, fits: Boolean, amount: Int) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (isBest) Ui.Up.copy(alpha = 0.10f) else Ui.Surface, shape)
            .let { if (isBest) it.border(1.dp, Ui.Up.copy(alpha = 0.5f), shape) else it }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ad.advertiserName,
                color = if (fits) Ui.Text else Ui.Dim,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                Formatting.price(ad.price),
                color = if (isBest) Ui.Up else if (fits) Ui.Text else Ui.Dim,
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
        }
        if (isBest) {
            Text(
                "Best rate for your order",
                color = Ui.Up, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(Ui.Up.copy(alpha = 0.15f), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Text(
            "Limit Rs ${Formatting.whole(ad.minFiat)} – ${Formatting.whole(ad.maxFiat)} · " +
                "${Formatting.usdt(ad.availableUsdt)} available",
            color = Ui.Dim, fontSize = 13.sp,
        )
        Text(
            "${Formatting.whole(ad.monthOrderCount)} orders this month · " +
                "${(ad.monthFinishRate * 100).toInt()}% completed · pay within ${ad.payTimeLimitMinutes} min",
            color = Ui.Dim, fontSize = 13.sp,
        )
        if (!fits) {
            val fiat = amount * ad.price
            Text(
                when {
                    ad.availableUsdt < amount -> "Only ${Formatting.usdt(ad.availableUsdt)} left"
                    fiat < ad.minFiat -> "Minimum order is Rs ${Formatting.whole(ad.minFiat)}"
                    else -> "Maximum order is Rs ${Formatting.whole(ad.maxFiat)}"
                },
                color = Ui.Amber, fontSize = 13.sp,
            )
        }
    }
}
