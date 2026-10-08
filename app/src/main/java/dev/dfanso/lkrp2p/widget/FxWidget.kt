package dev.dfanso.lkrp2p.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.Currencies
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.Trend
import dev.dfanso.lkrp2p.data.FxQuote
import dev.dfanso.lkrp2p.data.FxRange
import dev.dfanso.lkrp2p.data.FxRepository
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.render.ChartPainter

/**
 * A reference exchange rate for any pair (USD/LKR, EUR/USD, BTC/LKR, …), kept
 * apart from the P2P widget: these are daily mid-market rates, not what a
 * Binance trader will pay. Each instance has its own pair, range and theme.
 */
class FxWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = FxRepository.get(context)
        if (repo.lastFetchSec == null) runCatching { repo.refresh() }
        provideContent { FxContent() }
    }

    companion object {
        val FromKey = stringPreferencesKey("from")
        val ToKey = stringPreferencesKey("to")
        val RangeKey = stringPreferencesKey("range")
        val ThemeKey = RateWidget.ThemeKey
        val RefreshKey = longPreferencesKey("refreshedAt")

        suspend fun refreshAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(FxWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { it[RefreshKey] = System.currentTimeMillis() }
            }
            FxWidget().updateAll(context)
        }

        suspend fun hasInstances(context: Context) =
            GlanceAppWidgetManager(context).getGlanceIds(FxWidget::class.java).isNotEmpty()
    }
}

private val RangeParam = ActionParameters.Key<String>("range")

class SetFxRangeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val range = parameters[RangeParam] ?: return
        updateAppWidgetState(context, glanceId) { it[FxWidget.RangeKey] = range }
        FxWidget().update(context, glanceId)
    }
}

/** Turns USD/LKR into LKR/USD. */
class SwapFxPairAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val settings = Settings.get(context)
        updateAppWidgetState(context, glanceId) { prefs ->
            val from = prefs[FxWidget.FromKey] ?: settings.fxFrom
            val to = prefs[FxWidget.ToKey] ?: settings.fxTo
            prefs[FxWidget.FromKey] = to
            prefs[FxWidget.ToKey] = from
        }
        FxWidget().update(context, glanceId)
    }
}

class RefreshFxAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { FxRepository.get(context).refresh() }
        FxWidget.refreshAll(context)
    }
}

@SuppressLint("LocalContextConfigurationRead")
@Composable
private fun FxContent() {
    val context = LocalContext.current
    val size = LocalSize.current
    val prefs = currentState<Preferences>()
    val settings = Settings.get(context)
    val from = prefs[FxWidget.FromKey] ?: settings.fxFrom
    val to = prefs[FxWidget.ToKey] ?: settings.fxTo
    val range = FxRange.fromKey(prefs[FxWidget.RangeKey]) ?: FxRange.MONTH
    val refreshed = prefs[FxWidget.RefreshKey] ?: 0L

    // Archived days arrive in the background; each batch repaints the chart.
    var filled by remember { mutableIntStateOf(0) }
    LaunchedEffect(range) {
        FxRepository.get(context).backfill(range)
        filled++
    }
    val quote = remember(from, to, range, refreshed, filled) { FxRepository.get(context).quote(from, to, range) }
    val m = Metrics(size.width.value, size.height.value, context.resources.configuration.fontScale)

    WidgetFrame(prefs[FxWidget.ThemeKey], m, refreshed, openTab = "FX") { density ->
        when {
            m.heightDp >= 250 && m.widthDp >= 200 -> FxFullLayout(quote, m, density)
            m.heightDp >= 110 && m.widthDp >= 170 -> FxCompactLayout(quote, m, density)
            else -> FxTinyLayout(quote, m, density)
        }
    }
}

@Composable
private fun FxFullLayout(q: FxQuote, m: Metrics, density: Float) {
    val pad = (m.widthDp * 0.065f).clamp(14f, 24f)
    val innerW = m.widthDp - pad * 2
    val titleSize = (m.widthDp * 0.05f).clamp(13f, 18f)
    val titleH = titleSize * 1.6f
    val gap = (m.heightDp * 0.035f).clamp(6f, 18f)
    val total = Formatting.rate(q.rate)
    var big = (m.widthDp * 0.115f).clamp(24f, 46f)
    big = minOf(big, (innerW - 86f - big * 0.9f) / (total.length * 0.6f)).coerceAtLeast(16f)
    val rowH = big * 1.3f
    val swapH = 24f
    var segH = (m.heightDp * 0.075f).clamp(30f, 40f)
    val fixed = titleH + gap + rowH + swapH + rowH + gap
    var chartH = m.heightDp - pad * 2 - fixed - segH - 10f
    if (chartH < 70f) {
        segH = 0f
        chartH = m.heightDp - pad * 2 - fixed
    }

    Column(modifier = GlanceModifier.fillMaxSize().padding(pad.dp)) {
        FxTitleRow(q, m, titleSize, titleH, "Exchange rate · ${q.pair}")
        Spacer(GlanceModifier.height(gap.dp))
        FxAmountRow("1", q.from, m, big, rowH)
        Row(
            modifier = GlanceModifier.fillMaxWidth().height(swapH.dp).clickable(actionRunCallback<SwapFxPairAction>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(ImageProvider(R.drawable.ic_swap), contentDescription = "Swap currencies", modifier = GlanceModifier.size(18.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text(
                "Mid-market rate · tap to swap",
                style = TextStyle(color = Dim, fontSize = m.text(12.5f)),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            TrendChip(q.trend, m, 11.5f)
        }
        FxAmountRow(total, q.to, m, big, rowH)
        Spacer(GlanceModifier.height(gap.dp))
        if (segH > 0f) {
            WidgetSegmented(FxRange.entries, q.range, { it.label }, m, segH) {
                actionRunCallback<SetFxRangeAction>(actionParametersOf(RangeParam to it.key))
            }
            Spacer(GlanceModifier.height(10.dp))
        }
        if (chartH >= 40f) FxChart(q, innerW, chartH, density, showLabels = chartH >= 90f)
    }
}

@Composable
private fun FxCompactLayout(q: FxQuote, m: Metrics, density: Float) {
    val pad = (m.widthDp * 0.06f).clamp(10f, 18f)
    val innerW = m.widthDp - pad * 2
    val titleSize = (m.widthDp * 0.045f).clamp(11.5f, 15f)
    val titleH = titleSize * 1.6f
    val total = Formatting.rate(q.rate)
    var big = minOf((m.widthDp * 0.12f).clamp(22f, 40f), (m.heightDp * 0.24f).coerceAtLeast(20f))
    big = minOf(big, (innerW - 70f) / (total.length * 0.6f)).coerceAtLeast(16f)
    val rowH = big * 1.3f
    val captionH = 11.5f * 1.5f
    val chartH = m.heightDp - pad * 2 - titleH - rowH - captionH - 6f

    Column(modifier = GlanceModifier.fillMaxSize().padding(pad.dp)) {
        FxTitleRow(q, m, titleSize, titleH, q.pair, swappable = true)
        Row(modifier = GlanceModifier.fillMaxWidth().height(rowH.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                total,
                style = TextStyle(color = White, fontSize = m.text(big), fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            TrendChip(q.trend, m, 11.5f)
        }
        Text(
            "${q.to.uppercase()} per 1 ${q.from.uppercase()} · ${q.range.label}",
            style = TextStyle(color = Dim, fontSize = m.text(11.5f)),
            maxLines = 1,
            modifier = GlanceModifier.height(captionH.dp),
        )
        if (chartH >= 30f) {
            Spacer(GlanceModifier.height(6.dp))
            FxChart(q, innerW, chartH, density, showLabels = chartH >= 85f)
        }
    }
}

@Composable
private fun FxTinyLayout(q: FxQuote, m: Metrics, density: Float) {
    val pad = (m.heightDp * 0.14f).clamp(8f, 14f)
    val innerH = m.heightDp - pad * 2
    val showLabel = innerH >= 46f
    val sparkW = if (m.widthDp >= 240f) m.widthDp * 0.38f else 0f
    val textW = m.widthDp - pad * 2 - if (sparkW > 0f) sparkW + 8f else 0f
    val total = Formatting.rate(q.rate)
    var big = (innerH * if (showLabel) 0.5f else 0.62f).clamp(16f, 32f)
    big = minOf(big, textW / (total.length * 0.6f))

    Row(modifier = GlanceModifier.fillMaxSize().padding(pad.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            if (showLabel) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        q.pair,
                        style = TextStyle(color = Dim, fontSize = m.text(11f)),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<SwapFxPairAction>()),
                    )
                    TrendText(q.trend, m, 11f)
                }
            }
            Text(
                total,
                style = TextStyle(color = White, fontSize = m.text(big), fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        if (sparkW > 0f) {
            Spacer(GlanceModifier.width(8.dp))
            FxChart(q, sparkW, innerH, density, showLabels = false)
        }
    }
}

@Composable
private fun FxTitleRow(q: FxQuote, m: Metrics, size: Float, height: Float, title: String, swappable: Boolean = false) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(height.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = TextStyle(color = White, fontSize = m.text(size), fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().let {
                if (swappable) it.clickable(actionRunCallback<SwapFxPairAction>()) else it
            },
        )
        Text(
            q.date?.let(::shortDate) ?: "No data",
            style = TextStyle(color = Dim, fontSize = m.text(11f)),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(6.dp))
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Refresh",
            modifier = GlanceModifier.size(18.dp).clickable(actionRunCallback<RefreshFxAction>()),
        )
    }
}

/** "2026-10-07" as "7 Oct", the day the rate was published. */
private fun shortDate(date: String): String =
    java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(FxRepository.parseDay(date) * 1000))

@Composable
private fun FxAmountRow(value: String, code: String, m: Metrics, size: Float, height: Float) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(height.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            value,
            style = TextStyle(color = White, fontSize = m.text(size), fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(Currencies.badge(code), style = TextStyle(color = White, fontSize = m.text(16f)))
        Spacer(GlanceModifier.width(7.dp))
        Text(code.uppercase(), style = TextStyle(color = White, fontSize = m.text(17f), fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun FxChart(q: FxQuote, widthDp: Float, heightDp: Float, density: Float, showLabels: Boolean) {
    val colors = LocalWidgetColors.current
    val color = if (q.trend?.direction == Trend.Direction.DOWN) colors.down else colors.up
    val bitmap = remember(q, widthDp, heightDp, showLabels, color) {
        ChartPainter.bitmap(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            ChartPainter.Spec(
                points = q.series,
                window = if (q.range == FxRange.WEEK) ChartWindow.WEEK else ChartWindow.MONTH,
                nowSec = System.currentTimeMillis() / 1000,
                gapSec = q.range.stepDays * 86_400L * 4,
                lineColor = color,
                density = density,
                showLabels = showLabels,
                emptyMessage = if (q.rate == null) "Loading rates…" else "Loading history…",
                baseColor = colors.base,
                daily = true,
                durationSec = q.range.days * 86_400L,
            ),
        )
    }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "${q.pair} chart, ${q.range.label}",
        contentScale = ContentScale.FillBounds,
        modifier = GlanceModifier.width(widthDp.dp).height(heightDp.dp),
    )
}
