package dev.dfanso.lkrp2p.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.ColorFilter
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.Trend
import dev.dfanso.lkrp2p.data.RateSnapshot
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.data.Store
import dev.dfanso.lkrp2p.render.BackgroundPainter
import dev.dfanso.lkrp2p.render.ChartPainter
import dev.dfanso.lkrp2p.render.ColorTheme
import dev.dfanso.lkrp2p.render.ThemeColors
import dev.dfanso.lkrp2p.render.Palette
import dev.dfanso.lkrp2p.ui.MainActivity
import dev.dfanso.lkrp2p.work.CollectWorker
import java.text.DateFormat
import java.util.Date

/**
 * The home-screen widget. [SizeMode.Exact] hands us the real size of every
 * instance, so one widget adapts from a 2x1 strip to a full-screen card and
 * the chart bitmap is drawn at exactly the pixels it will occupy.
 */
class RateWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // First appearance with an empty database: fetch right away rather than
        // showing dashes until the next periodic slot.
        val settings = Settings.get(context)
        if (Store.get(context).latest(settings.side, settings.amountUsdt) == null) {
            CollectWorker.runNow(context)
        }
        provideContent { WidgetContent() }
    }

    companion object {
        val SideKey = stringPreferencesKey("side")
        val WindowKey = stringPreferencesKey("window")
        val RefreshKey = longPreferencesKey("refreshedAt")

        /** This instance's own theme; absent follows the widget theme in Settings. */
        val ThemeKey = stringPreferencesKey("theme")

        /** Repaint every instance with fresh data from the store. */
        suspend fun refreshAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(RateWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { it[RefreshKey] = System.currentTimeMillis() }
            }
            RateWidget().updateAll(context)
        }
    }
}

private val WindowParam = ActionParameters.Key<String>("window")

class SetWindowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val window = parameters[WindowParam] ?: return
        updateAppWidgetState(context, glanceId) { it[RateWidget.WindowKey] = window }
        RateWidget().update(context, glanceId)
    }
}

class FlipSideAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = Side.fromWire(prefs[RateWidget.SideKey]) ?: Settings.get(context).side
            prefs[RateWidget.SideKey] = current.opposite.wire
        }
        RateWidget().update(context, glanceId)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CollectWorker.runNow(context)
    }
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

internal val White = ColorProvider(Color.White)
internal val Dim = ColorProvider(Color(Palette.TEXT_DIM))
internal val Amber = ColorProvider(Color(Palette.STALE))

/** The theme of the widget being composed. */
internal val LocalWidgetColors = staticCompositionLocalOf { ColorTheme.DEFAULT.preset!! }

/** Size-dependent metrics. Text is sized in dp, not sp, so it fits the box it was measured for. */
internal class Metrics(val widthDp: Float, val heightDp: Float, private val fontScale: Float) {
    fun text(dp: Float): TextUnit = (dp / fontScale).sp
}

internal fun Float.clamp(lo: Float, hi: Float) = coerceIn(lo, hi)

// Glance has no LocalConfiguration; its LocalContext is the right source here.
@SuppressLint("LocalContextConfigurationRead")
@Composable
private fun WidgetContent() {
    val context = LocalContext.current
    val size = LocalSize.current
    val prefs = currentState<Preferences>()
    val settings = Settings.get(context)
    val side = Side.fromWire(prefs[RateWidget.SideKey]) ?: settings.side
    val window = ChartWindow.fromKey(prefs[RateWidget.WindowKey]) ?: ChartWindow.DAY
    val refreshed = prefs[RateWidget.RefreshKey] ?: 0L

    val data = remember(side, window, refreshed) { RateSnapshot.load(context, side, window) }
    val m = Metrics(size.width.value, size.height.value, context.resources.configuration.fontScale)

    WidgetFrame(prefs[RateWidget.ThemeKey], m, refreshed) { density ->
        when {
            m.heightDp >= 250 && m.widthDp >= 200 -> FullLayout(data, m, density)
            m.heightDp >= 110 && m.widthDp >= 170 -> CompactLayout(data, m, density)
            else -> TinyLayout(data, m, density)
        }
    }
}

/**
 * The themed glow card every widget sits on. [themeKey] is the instance's own
 * theme, if it has one; [refreshed] invalidates the cached colours so a theme
 * change in Settings shows on the next repaint.
 */
// Glance's own LocalContext; the Compose lint check does not apply to it.
@SuppressLint("LocalContextConfigurationRead", "LocalContextResourcesRead")
@Composable
internal fun WidgetFrame(
    themeKey: String?,
    m: Metrics,
    refreshed: Long,
    openTab: String? = null,
    content: @Composable (density: Float) -> Unit,
) {
    val context = LocalContext.current
    val res = context.resources
    val density = res.displayMetrics.density
    val settings = Settings.get(context)
    val theme = ColorTheme.fromKey(themeKey) ?: settings.effectiveWidgetTheme
    val opacity = settings.widgetOpacity
    val colors = remember(theme, refreshed) { theme.colors(context) }

    val systemCorner = if (Build.VERSION.SDK_INT >= 31) {
        res.getDimension(android.R.dimen.system_app_widget_background_radius) / density
    } else 18f
    // A one-row strip with the full system radius turns into a pill.
    val cornerDp = minOf(systemCorner, m.heightDp * 0.24f)
    val background = remember(m.widthDp, m.heightDp, cornerDp, colors, opacity) {
        BackgroundPainter.bitmap(
            (m.widthDp * density).toInt(), (m.heightDp * density).toInt(), cornerDp * density,
            colors.withOpacity(opacity),
        )
    }

    CompositionLocalProvider(LocalWidgetColors provides colors) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(cornerDp.dp)
                .background(ImageProvider(background), ContentScale.FillBounds)
                .clickable(
                    actionStartActivity<MainActivity>(
                        openTab?.let { actionParametersOf(ActionParameters.Key<String>(MainActivity.EXTRA_TAB) to it) }
                            ?: actionParametersOf()
                    )
                ),
        ) {
            content(density)
        }
    }
}

/** The reference design: 1 USDT, Sell/Buy switch, its price in LKR, period control, chart. */
@Composable
private fun FullLayout(data: RateSnapshot, m: Metrics, density: Float) {
    val pad = (m.widthDp * 0.065f).clamp(14f, 24f)
    val innerW = m.widthDp - pad * 2
    val titleSize = (m.widthDp * 0.05f).clamp(13f, 18f)
    val titleH = titleSize * 1.6f
    val gap = (m.heightDp * 0.035f).clamp(6f, 18f)

    val amountText = "1"
    val totalText = Formatting.price(data.price)
    val badgeW = 86f
    var big = (m.widthDp * 0.115f).clamp(24f, 46f)
    val longest = maxOf(amountText.length, totalText.length)
    // Shrink the numerals before they collide with the currency badge.
    big = minOf(big, (innerW - badgeW - big * 0.9f) / (longest * 0.6f)).coerceAtLeast(16f)
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
        TitleRow(data, m, titleSize, titleH, showSideToggle = false)
        Spacer(GlanceModifier.height(gap.dp))
        AmountRow(symbol = "₮", value = amountText, m = m, size = big, height = rowH) {
            CurrencyBadge(icon = { CoinGlyph(m) }, code = "USDT", m = m)
        }
        SwapRow(data, m, swapH)
        AmountRow(symbol = "Rs", value = totalText, m = m, size = big, height = rowH) {
            CurrencyBadge(icon = { Text("🇱🇰", style = TextStyle(fontSize = m.text(15f))) }, code = "LKR", m = m)
        }
        Spacer(GlanceModifier.height(gap.dp))
        if (segH > 0f) {
            Segmented(data.window, m, segH)
            Spacer(GlanceModifier.height(10.dp))
        }
        if (chartH >= 40f) Chart(data, innerW, chartH, density, showLabels = chartH >= 90f)
    }
}

/** Headline rate per USDT with a sparkline underneath. */
@Composable
private fun CompactLayout(data: RateSnapshot, m: Metrics, density: Float) {
    val pad = (m.widthDp * 0.06f).clamp(10f, 18f)
    val innerW = m.widthDp - pad * 2
    val titleSize = (m.widthDp * 0.045f).clamp(11.5f, 15f)
    val titleH = titleSize * 1.6f
    val big = minOf((m.widthDp * 0.12f).clamp(22f, 40f), (m.heightDp * 0.24f).coerceAtLeast(20f))
    val rowH = big * 1.3f
    val captionSize = 11.5f
    val captionH = captionSize * 1.5f
    val chartH = m.heightDp - pad * 2 - titleH - rowH - captionH - 6f

    Column(modifier = GlanceModifier.fillMaxSize().padding(pad.dp)) {
        TitleRow(data, m, titleSize, titleH, showSideToggle = true)
        RateRow(data, m, big, rowH)
        Text(
            "per 1 USDT · ${data.side.label.lowercase()} rate",
            style = TextStyle(color = Dim, fontSize = m.text(captionSize)),
            maxLines = 1,
            modifier = GlanceModifier.height(captionH.dp),
        )
        if (chartH >= 30f) {
            Spacer(GlanceModifier.height(6.dp))
            Chart(data, innerW, chartH, density, showLabels = chartH >= 85f)
        }
    }
}

/** 2x1 and similar strips: just the rate, plus a sparkline when there is room. */
@Composable
private fun TinyLayout(data: RateSnapshot, m: Metrics, density: Float) {
    val pad = (m.heightDp * 0.14f).clamp(8f, 14f)
    val innerH = m.heightDp - pad * 2
    val showLabel = innerH >= 46f
    val sparkW = if (m.widthDp >= 240f) m.widthDp * 0.38f else 0f
    val textW = m.widthDp - pad * 2 - if (sparkW > 0f) sparkW + 8f else 0f
    // Six glyphs of the price must fit; numerals run about 0.58em wide.
    var big = (innerH * if (showLabel) 0.5f else 0.62f).clamp(16f, 32f)
    big = minOf(big, textW / (Formatting.price(data.price).length * 0.6f))
    val chipInline = textW >= big * 3.6f + 84f

    Row(
        modifier = GlanceModifier.fillMaxSize().padding(pad.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            if (showLabel) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (chipInline) "USDT/LKR · ${data.side.label}" else data.side.label,
                        style = TextStyle(color = Dim, fontSize = m.text(11f)),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight().clickable(actionRunCallback<FlipSideAction>()),
                    )
                    if (!chipInline) TrendText(data.trend, m, 11f)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Formatting.price(data.price),
                    style = TextStyle(color = White, fontSize = m.text(big), fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                if (chipInline) {
                    Spacer(GlanceModifier.width(6.dp))
                    TrendChip(data.trend, m, 11f)
                }
            }
        }
        if (sparkW > 0f) {
            Spacer(GlanceModifier.width(8.dp))
            Chart(data, sparkW, innerH, density, showLabels = false)
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun TitleRow(data: RateSnapshot, m: Metrics, size: Float, height: Float, showSideToggle: Boolean) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (showSideToggle) "USDT/LKR · ${data.side.label}" else "P2P Rate · USDT/LKR",
            style = TextStyle(color = White, fontSize = m.text(size), fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().let {
                if (showSideToggle) it.clickable(actionRunCallback<FlipSideAction>()) else it
            },
        )
        val time = data.sample?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timestampSec * 1000)) }
        Text(
            when {
                time == null -> "No data"
                data.isStale -> "Stale · $time"
                else -> time
            },
            style = TextStyle(color = if (data.isStale) Amber else Dim, fontSize = m.text(11f)),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(6.dp))
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Refresh",
            modifier = GlanceModifier.size(18.dp).clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

@Composable
private fun AmountRow(
    symbol: String,
    value: String,
    m: Metrics,
    size: Float,
    height: Float,
    badge: @Composable () -> Unit,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            symbol,
            style = TextStyle(color = White, fontSize = m.text(size * 0.55f), fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.width((size * 0.22f).dp))
        Text(
            value,
            style = TextStyle(color = White, fontSize = m.text(size), fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        badge()
    }
}

@Composable
private fun CurrencyBadge(icon: @Composable () -> Unit, code: String, m: Metrics) {
    Row(
        modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(GlanceModifier.width(7.dp))
        Text(code, style = TextStyle(color = White, fontSize = m.text(17f), fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.width(5.dp))
        Image(ImageProvider(R.drawable.ic_chevron_down), contentDescription = null, modifier = GlanceModifier.size(16.dp))
    }
}

@Composable
private fun CoinGlyph(m: Metrics) {
    Box(
        modifier = GlanceModifier.size(20.dp).background(
            ImageProvider(R.drawable.bg_coin),
            colorFilter = ColorFilter.tint(ColorProvider(Color(LocalWidgetColors.current.coin))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text("₮", style = TextStyle(color = White, fontSize = m.text(12f), fontWeight = FontWeight.Bold))
    }
}

/** The ↓↑ row of the reference. Tapping it flips this widget between Sell and Buy. */
@Composable
private fun SwapRow(data: RateSnapshot, m: Metrics, height: Float) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height.dp).clickable(actionRunCallback<FlipSideAction>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(R.drawable.ic_swap), contentDescription = "Switch Sell/Buy", modifier = GlanceModifier.size(18.dp))
        Spacer(GlanceModifier.width(8.dp))
        Text(
            "${data.side.label} rate · tap to switch",
            style = TextStyle(color = Dim, fontSize = m.text(12.5f)),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        TrendChip(data.trend, m, 11.5f)
    }
}

@Composable
private fun RateRow(data: RateSnapshot, m: Metrics, size: Float, height: Float) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(height.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Rs", style = TextStyle(color = White, fontSize = m.text(size * 0.5f), fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.width((size * 0.18f).dp))
        Text(
            Formatting.price(data.price),
            style = TextStyle(color = White, fontSize = m.text(size), fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        TrendChip(data.trend, m, 11.5f)
    }
}

// The pill stays neutral so it suits every theme; the text carries the colour.
private fun Trend.chipStyle(colors: ThemeColors) = when (direction) {
    Trend.Direction.UP -> Triple(R.drawable.bg_chip_flat, "▲", colors.up)
    Trend.Direction.DOWN -> Triple(R.drawable.bg_chip_flat, "▼", colors.down)
    Trend.Direction.FLAT -> Triple(R.drawable.bg_chip_flat, "■", Palette.TEXT_DIM)
}

private fun Trend.label(arrow: String) = "$arrow ${Formatting.percent(percent).trimStart('+', '-')}"

@Composable
internal fun TrendChip(trend: Trend?, m: Metrics, size: Float) {
    if (trend == null) return
    val (bg, arrow, color) = trend.chipStyle(LocalWidgetColors.current)
    Box(
        modifier = GlanceModifier.background(ImageProvider(bg)).padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        TrendText(trend, m, size, arrow, color)
    }
}

/** The chip's text without the pill, for strips too narrow to hold one. */
@Composable
internal fun TrendText(
    trend: Trend?,
    m: Metrics,
    size: Float,
    arrow: String = trend?.chipStyle(LocalWidgetColors.current)?.second.orEmpty(),
    color: Int = trend?.chipStyle(LocalWidgetColors.current)?.third ?: Palette.TEXT_DIM,
) {
    if (trend == null) return
    Text(
        trend.label(arrow),
        style = TextStyle(color = ColorProvider(Color(color)), fontSize = m.text(size), fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
}

/** Day | Week | Month, as in the reference's Week | Month | Year. */
@Composable
private fun Segmented(selected: ChartWindow, m: Metrics, height: Float) =
    WidgetSegmented(ChartWindow.entries, selected, { it.label }, m, height) {
        actionRunCallback<SetWindowAction>(actionParametersOf(WindowParam to it.key))
    }

/** Day | Week | Month style control; [actionFor] is what tapping a segment does. */
@Composable
internal fun <T> WidgetSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    m: Metrics,
    height: Float,
    actionFor: (T) -> Action,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(height.dp)
            .background(ImageProvider(R.drawable.bg_segment_track))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height((height - 6f).dp)
                    .let { if (isSelected) it.background(ImageProvider(R.drawable.bg_segment_selected)) else it }
                    .clickable(actionFor(option)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = TextStyle(
                        color = if (isSelected) White else Dim,
                        fontSize = m.text(13.5f),
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Chart(data: RateSnapshot, widthDp: Float, heightDp: Float, density: Float, showLabels: Boolean) {
    val colors = LocalWidgetColors.current
    val color = if (data.trend?.direction == Trend.Direction.DOWN) colors.down else colors.up
    val bitmap = remember(data, widthDp, heightDp, showLabels, color) {
        ChartPainter.bitmap(
            (widthDp * density).toInt(),
            (heightDp * density).toInt(),
            ChartPainter.Spec(
                points = data.series,
                window = data.window,
                nowSec = data.nowSec,
                gapSec = data.gapSec,
                lineColor = color,
                density = density,
                showLabels = showLabels,
                emptyMessage = if (data.sample == null) "Collecting data…" else "Building history…",
                baseColor = colors.base,
            ),
        )
    }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "${data.side.label} rate chart, ${data.window.label}",
        contentScale = ContentScale.FillBounds,
        modifier = GlanceModifier.width(widthDp.dp).height(heightDp.dp),
    )
}
