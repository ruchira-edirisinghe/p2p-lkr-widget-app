package dev.dfanso.lkrp2p.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.Converter
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.SeriesPoint
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.Trend
import dev.dfanso.lkrp2p.data.RateSnapshot
import dev.dfanso.lkrp2p.render.BackgroundPainter
import dev.dfanso.lkrp2p.render.ChartPainter
import dev.dfanso.lkrp2p.render.Palette
import dev.dfanso.lkrp2p.widget.RateWidgetReceiver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateScreen(state: UiState, vm: MainViewModel, onOpenSettings: () -> Unit) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("USDT → LKR", color = Ui.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Binance P2P · ${state.payment.displayName}", color = Ui.Dim, fontSize = 13.sp)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), "Settings", tint = Ui.Text)
                }
            }

            SideSwitch(state, vm)
            state.error?.let { ErrorBanner(it, onRetry = vm::refresh) }
            ConverterCard(state, vm)
            ChartCard(state, vm)
            StatsPanel(state)
            StatusLine(state)
            if (!state.hasWidget) WidgetPromo()
            Text(
                "Rates come from the public Binance P2P order book. Not affiliated with Binance. Not financial advice.",
                color = Ui.Faint, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/** Both live rates at a glance; picking one sets what the rest of the screen shows. */
@Composable
private fun SideSwitch(state: UiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Segmented(
            options = Side.entries,
            selected = state.side,
            label = { if (it == Side.SELL) "Sell USDT" else "Buy USDT" },
            sublabel = { Formatting.price(state.latest[it]?.fillablePrice) },
            onSelect = vm::setSide,
            height = 62.dp,
        )
        Text(
            if (state.side == Side.SELL) "You give USDT and receive LKR in your bank."
            else "You pay LKR from your bank and receive USDT.",
            color = Ui.Dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** The widget's card, made interactive: type an amount in either currency. */
@Composable
private fun ConverterCard(state: UiState, vm: MainViewModel) {
    val shape = RoundedCornerShape(28.dp)
    val quote = state.quote
    val input = state.converter
    val selling = state.side == Side.SELL
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape)) {
        val big = (maxWidth.value * 0.11f).coerceIn(30f, 46f)
        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { BackgroundPainter.draw(it.nativeCanvas, size.width, size.height, 28.dp.toPx()) }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val topLabel = when {
                input.isUsdt -> if (selling) "You sell" else "You buy"
                else -> if (selling) "You receive" else "You pay"
            }
            val bottomLabel = when {
                input.isUsdt -> if (selling) "You receive" else "You pay"
                else -> if (selling) "You sell" else "You buy"
            }
            Text(topLabel, color = Ui.Dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (input.isUsdt) "₮" else "Rs", color = Ui.Text, fontSize = (big * 0.55f).sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = input.text,
                    onValueChange = vm::setConverterText,
                    singleLine = true,
                    textStyle = TextStyle(color = Ui.Text, fontSize = big.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(Ui.Up),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    visualTransformation = GroupDigits,
                    modifier = Modifier.weight(1f),
                    decorationBox = { field ->
                        if (input.text.isEmpty()) Text("0", color = Ui.Faint, fontSize = big.sp, fontWeight = FontWeight.Bold)
                        field()
                    },
                )
                CurrencyBadge(if (input.isUsdt) "USDT" else "LKR")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = Color(0x33FFFFFF))
                Box(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0x26FFFFFF))
                        .clickable(onClickLabel = "Swap currencies", onClick = vm::swapConverter),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_swap), "Swap currencies", tint = Ui.Text, modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(Modifier.weight(1f), color = Color(0x33FFFFFF))
            }

            Text(bottomLabel, color = Ui.Dim, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (input.isUsdt) "Rs" else "₮", color = Ui.Text, fontSize = (big * 0.55f).sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
                Text(
                    quote?.let { Formatting.price(if (input.isUsdt) it.lkr else it.usdt) } ?: "—",
                    color = Ui.Text, fontSize = big.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                CurrencyBadge(if (input.isUsdt) "LKR" else "USDT")
            }

            Spacer(Modifier.height(4.dp))
            QuickAmounts(input.isUsdt, input.text, vm::setConverterText)
            Spacer(Modifier.height(2.dp))
            RateNote(quote, state.orderSize)
        }
    }
}

@Composable
private fun QuickAmounts(isUsdt: Boolean, current: String, onPick: (String) -> Unit) {
    val amounts = if (isUsdt) listOf(1, 100, 500, 1_000) else listOf(10_000, 50_000, 100_000, 500_000)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        amounts.forEach { amount ->
            val picked = current == amount.toString()
            val label = if (isUsdt) Formatting.whole(amount) else "${amount / 1_000}k"
            Text(
                label,
                color = if (picked) Ui.Bg else Ui.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (picked) Ui.Up else Color(0x1FFFFFFF))
                    .clickable { onPick(amount.toString()) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** Says which ad the rate came from, so a surprising number is explainable. */
@Composable
private fun RateNote(quote: Converter.Quote?, orderSize: Int) {
    if (quote == null) {
        Text("Waiting for the first rate…", color = Ui.Dim, fontSize = 13.sp)
        return
    }
    val size = Formatting.usdt(orderSize.toDouble())
    val detail = when (quote.basis) {
        Converter.Basis.EXACT -> "Best ad for this amount: ${quote.ad?.advertiserName}"
        Converter.Basis.TOO_SMALL -> "Rate from the best ad that takes $size orders"
        Converter.Basis.TOO_LARGE -> "No single ad takes this much. Shown at the $size rate."
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("1 USDT = ${Formatting.price(quote.rate)} LKR", color = Ui.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = Ui.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun ChartCard(state: UiState, vm: MainViewModel) {
    val rate = state.rate
    var touched by remember(rate) { mutableStateOf<SeriesPoint?>(null) }
    val density = LocalDensity.current.density
    val color = if (rate?.trend?.direction == Trend.Direction.DOWN) Palette.DOWN else Palette.UP

    Panel(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
            val point = touched
            if (point != null) {
                Column(Modifier.weight(1f)) {
                    Text(Formatting.price(point.price), color = Ui.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(pointTime(point, state.window), color = Ui.Dim, fontSize = 13.sp)
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.window) {
                            ChartWindow.DAY -> "Past 24 hours"
                            ChartWindow.WEEK -> "Past 7 days"
                            ChartWindow.MONTH -> "Past 30 days"
                        },
                        color = Ui.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                    val trend = rate?.trend
                    Text(
                        if (trend == null) "Touch the chart to see past rates" else "${Formatting.signedDelta(trend.delta)} LKR",
                        color = Ui.Dim, fontSize = 13.sp,
                    )
                }
                TrendChip(rate?.trend)
            }
        }

        Segmented(ChartWindow.entries, state.window, { it.label }, vm::setWindow, height = 40.dp)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chartHeight = (maxWidth.value * 0.55f).coerceIn(180f, 300f).dp
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    // Press to read one point; drag sideways to scrub. Vertical
                    // drags still scroll the page.
                    .pointerInput(rate) {
                        if (rate == null) return@pointerInput
                        detectTapGestures(onPress = {
                            touched = touchedPoint(rate, color, density, size, it.x)
                            tryAwaitRelease()
                            touched = null
                        })
                    }
                    .pointerInput(rate) {
                        if (rate == null) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { touched = touchedPoint(rate, color, density, size, it.x) },
                            onDragEnd = { touched = null },
                            onDragCancel = { touched = null },
                        ) { change, _ ->
                            touched = touchedPoint(rate, color, density, size, change.position.x)
                            change.consume()
                        }
                    },
            ) {
                if (rate == null) return@Canvas
                drawIntoCanvas {
                    ChartPainter.draw(it.nativeCanvas, size.width, size.height, chartSpec(rate, color, density, touched))
                }
            }
        }

        val prices = rate?.series?.map { it.price }.orEmpty()
        if (prices.isNotEmpty()) {
            Row {
                Stat("Low", Formatting.price(prices.min()), Modifier.weight(1f))
                Stat("High", Formatting.price(prices.max()), Modifier.weight(1f))
                Stat("Average", Formatting.price(prices.average()), Modifier.weight(1f))
            }
        }
    }
}

private fun touchedPoint(rate: RateSnapshot, color: Int, density: Float, size: IntSize, x: Float): SeriesPoint? =
    ChartPainter.geometry(size.width.toFloat(), size.height.toFloat(), chartSpec(rate, color, density, null))
        .nearest(rate.series, x)

private fun chartSpec(rate: RateSnapshot, color: Int, density: Float, highlight: SeriesPoint?) =
    ChartPainter.Spec(
        points = rate.series,
        window = rate.window,
        nowSec = rate.nowSec,
        gapSec = rate.gapSec,
        lineColor = color,
        density = density,
        showLabels = true,
        emptyMessage = if (rate.sample == null) "Collecting data…" else "Building history…",
        highlight = highlight,
    )

private fun pointTime(point: SeriesPoint, window: ChartWindow): String {
    val pattern = if (window == ChartWindow.DAY) "HH:mm" else "EEE d MMM, HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(point.timestampSec * 1000))
}

@Composable
private fun StatsPanel(state: UiState) {
    val sample = state.latest[state.side]
    Panel {
        Row {
            Stat(
                "Buy − sell spread",
                state.spread?.let { "${Formatting.price(it)} LKR" } ?: "—",
                Modifier.weight(1f),
            )
            Stat("Top ad", Formatting.price(sample?.topPrice), Modifier.weight(1f))
            Stat("Median of top 10", Formatting.price(sample?.medianTop10), Modifier.weight(1f))
        }
        Text(
            "The rate uses the best ad that accepts ${Formatting.usdt(state.orderSize.toDouble())} orders. " +
                "The top ad often has a high minimum, so it can differ.",
            color = Ui.Dim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatusLine(state: UiState) {
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val sample = state.latest[state.side]
    val stale = state.rate?.isStale == true && sample != null
    Text(
        buildString {
            append(if (sample == null) "No rate yet" else "Updated ${Formatting.relativeAge(sample.timestampSec, now)}")
            append(" · refreshes every ${state.pollMinutes} min · pull down to refresh now")
        },
        color = if (stale) Ui.Amber else Ui.Dim,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ui.Down.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .border(1.dp, Ui.Down.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Ui.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry", color = Ui.Down) }
    }
}

@Composable
private fun WidgetPromo() {
    val context = LocalContext.current
    val manager = AppWidgetManager.getInstance(context)
    Panel {
        Text("Put the rate on your home screen", color = Ui.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "The widget updates by itself and you can resize it, from a slim strip to a full card.",
            color = Ui.Dim, fontSize = 14.sp,
        )
        if (manager.isRequestPinAppWidgetSupported) {
            Button(
                onClick = { manager.requestPinAppWidget(ComponentName(context, RateWidgetReceiver::class.java), null, null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add widget") }
        } else {
            Text(
                "Long-press your home screen, tap Widgets, then find USDT/LKR Rate.",
                color = Ui.Text, fontSize = 14.sp,
            )
        }
    }
}

/** Shows "100000.5" as "100,000.5" while the stored text stays plain digits. */
private object GroupDigits : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val intLen = raw.indexOf('.').let { if (it < 0) raw.length else it }
        // toTransformed[i] is where raw offset i lands once commas are added.
        val toTransformed = IntArray(raw.length + 1)
        val out = StringBuilder()
        for (i in raw.indices) {
            if (i in 1 until intLen && (intLen - i) % 3 == 0) out.append(',')
            toTransformed[i] = out.length
            out.append(raw[i])
        }
        toTransformed[raw.length] = out.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = toTransformed[offset]
            override fun transformedToOriginal(offset: Int) =
                toTransformed.indexOfFirst { it >= offset }.let { if (it < 0) raw.length else it }
        }
        return TransformedText(AnnotatedString(out.toString()), mapping)
    }
}
