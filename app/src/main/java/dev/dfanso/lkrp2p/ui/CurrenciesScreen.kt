package dev.dfanso.lkrp2p.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.Currencies
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.Trend
import dev.dfanso.lkrp2p.data.FxQuote
import dev.dfanso.lkrp2p.data.FxRange
import dev.dfanso.lkrp2p.render.BackgroundPainter
import dev.dfanso.lkrp2p.render.ChartPainter
import dev.dfanso.lkrp2p.widget.FxWidgetReceiver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrenciesScreen(state: FxUiState, vm: FxViewModel, onOpenSettings: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }

    PullToRefreshBox(isRefreshing = state.loading, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Currencies", color = Ui.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Daily mid-market rates for any pair. Not P2P prices.", color = Ui.Dim, fontSize = 14.sp)
                }
                IconButton(onClick = onOpenSettings) { Icon(painterResource(R.drawable.ic_settings), "Settings", tint = Ui.Text) }
            }
            state.error?.let { Text(it, color = Ui.Amber, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
            FxConverterCard(state, vm) { picking = it }
            FxChartCard(state, vm)
            FavoritesCard(state, vm)
            FxActions(state, vm)
        }
    }

    picking?.let { which ->
        CurrencyPickerDialog(
            title = if (which == "from") "From currency" else "To currency",
            currencies = state.currencies,
            selected = if (which == "from") state.from else state.to,
            onDismiss = { picking = null },
        ) { code ->
            if (which == "from") vm.setPair(code, state.to) else vm.setPair(state.from, code)
            picking = null
        }
    }
}

@Composable
private fun FxConverterCard(state: FxUiState, vm: FxViewModel, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val rate = state.quote?.rate
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape)) {
        val big = (maxWidth.value * 0.1f).coerceIn(28f, 42f)
        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { BackgroundPainter.draw(it.nativeCanvas, size.width, size.height, 28.dp.toPx(), Ui.colors) }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.name(state.from), color = Ui.Dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = state.amountText,
                    onValueChange = vm::setAmount,
                    singleLine = true,
                    textStyle = TextStyle(color = Ui.Text, fontSize = big.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(Ui.Up),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                CurrencyChip(state.from, { onPick("from") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::swap) { Icon(painterResource(R.drawable.ic_swap), "Swap currencies", tint = Ui.Text) }
                Text(
                    rate?.let { "1 ${state.from.uppercase()} = ${Formatting.rate(it)} ${state.to.uppercase()}" } ?: "Loading rates…",
                    color = Ui.Dim, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                TrendChip(state.quote?.trend)
            }
            Text(vm.name(state.to), color = Ui.Dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val result = state.amount?.let { a -> rate?.let { a * it } }
                Text(
                    Formatting.rate(result),
                    color = Ui.Text, fontSize = big.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                CurrencyChip(state.to, { onPick("to") })
            }
            state.quote?.date?.let {
                Text("Rate published $it · tap ↓↑ to swap", color = Ui.Faint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FxChartCard(state: FxUiState, vm: FxViewModel) {
    val quote = state.quote
    val density = LocalDensity.current.density
    val color = if (quote?.trend?.direction == Trend.Direction.DOWN) Ui.colors.down else Ui.colors.up
    Panel(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(quote?.pair ?: "", color = Ui.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val trend = quote?.trend
                Text(
                    if (trend == null) "Loading history…"
                    else "${Formatting.percent(trend.percent)} over ${state.range.label}",
                    color = Ui.Dim, fontSize = 13.sp,
                )
            }
            TrendChip(quote?.trend)
        }
        Segmented(FxRange.entries, state.range, { it.label }, vm::setRange, height = 40.dp)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chartHeight = (maxWidth.value * 0.5f).coerceIn(170f, 280f).dp
            val base = Ui.Surface.toArgb()
            Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
                if (quote == null) return@Canvas
                drawIntoCanvas {
                    ChartPainter.draw(
                        it.nativeCanvas, size.width, size.height,
                        ChartPainter.Spec(
                            points = quote.series,
                            window = if (state.range == FxRange.WEEK) ChartWindow.WEEK else ChartWindow.MONTH,
                            nowSec = System.currentTimeMillis() / 1000,
                            gapSec = state.range.stepDays * 86_400L * 4,
                            lineColor = color,
                            density = density,
                            showLabels = true,
                            emptyMessage = "Loading history…",
                            baseColor = base,
                            daily = true,
                            durationSec = state.range.days * 86_400L,
                        ),
                    )
                }
            }
        }
        val rates = quote?.series?.map { it.price }.orEmpty()
        if (rates.isNotEmpty()) {
            Row {
                Stat("Low", Formatting.rate(rates.min()), Modifier.weight(1f))
                Stat("High", Formatting.rate(rates.max()), Modifier.weight(1f))
                Stat("Average", Formatting.rate(rates.average()), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FavoritesCard(state: FxUiState, vm: FxViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            SectionLabel("Favourites", Modifier.weight(1f))
            val starred = state.pairKey in state.favorites.map { "${it.from}/${it.to}" }
            TextButton(onClick = vm::toggleFavorite) {
                Text(if (starred) "★ Remove ${state.from.uppercase()}/${state.to.uppercase()}" else "☆ Add ${state.from.uppercase()}/${state.to.uppercase()}")
            }
        }
        Panel(padding = 8.dp) {
            if (state.favorites.isEmpty()) {
                Text("Star a pair to keep it here.", color = Ui.Dim, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }
            state.favorites.forEach { q -> FavoriteRow(q, onOpen = { vm.setPair(q.from, q.to) }, onRemove = { vm.removeFavorite("${q.from}/${q.to}") }) }
        }
    }
}

@Composable
private fun FavoriteRow(q: FxQuote, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Currencies.badge(q.from) + " " + Currencies.badge(q.to), fontSize = 16.sp, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(q.pair, color = Ui.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${Formatting.rate(q.rate)} · 1W change", color = Ui.Dim, fontSize = 13.sp)
        }
        TrendChip(q.trend)
        IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_delete), "Remove ${q.pair}", tint = Ui.Faint) }
    }
}

@Composable
private fun FxActions(state: FxUiState, vm: FxViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) {
            OutlinedButton(
                onClick = {
                    // A new widget starts on the converter's pair, which setPair has saved.
                    manager.requestPinAppWidget(ComponentName(context, FxWidgetReceiver::class.java), null, null)
                },
                modifier = Modifier.weight(1f),
            ) { Text("Add ${state.from.uppercase()}/${state.to.uppercase()} widget") }
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/csv")
                        .putExtra(Intent.EXTRA_SUBJECT, "${state.from.uppercase()}/${state.to.uppercase()} rates")
                        .putExtra(Intent.EXTRA_TEXT, vm.csv())
                    context.startActivity(Intent.createChooser(send, "Export rates"))
                }
            },
        ) { Text("Export CSV") }
    }
    Text(
        "Rates: fawazahmed0/exchange-api (open source, daily). Saved on this phone for offline use.",
        color = Ui.Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
    )
}

