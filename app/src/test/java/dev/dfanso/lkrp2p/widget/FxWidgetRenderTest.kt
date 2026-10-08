package dev.dfanso.lkrp2p.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import dev.dfanso.lkrp2p.core.FxDay
import dev.dfanso.lkrp2p.data.FxRange
import dev.dfanso.lkrp2p.data.FxRepository
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.render.ColorTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * Renders the exchange-rate widget, and the P2P widget in each theme, into
 * app/build/widget-shots/ for checking by eye. Rates come from an offline
 * source, so no network is touched.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xxhdpi", application = Application::class)
class FxWidgetRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun seed(): Unit = runBlocking {
        val today = System.currentTimeMillis()
        val repo = FxRepository.installForTests(context) { date ->
            val day = date ?: FxRepository.dayString(today)
            val ago = ((today / 86_400_000L) - FxRepository.parseDay(day) / 86_400).toDouble()
            // A gentle LKR slide against the dollar with some daily noise.
            val lkr = 300.4 - ago * 0.05 + 0.6 * sin(ago / 4)
            FxDay(day, mapOf("usd" to 1.0, "lkr" to lkr, "eur" to 0.86 + 0.004 * sin(ago / 6), "btc" to 0.0000081))
        }
        repo.refresh()
        FxRange.entries.forEach { repo.backfill(it) }
        Settings.get(context).apply { fxFrom = "usd"; fxTo = "lkr" }
    }

    @Test
    fun quoteCrossesThroughTheDollar() {
        val q = FxRepository.get(context).quote("eur", "lkr")
        val usdLkr = FxRepository.get(context).quote("usd", "lkr").rate!!
        val eurUsd = FxRepository.get(context).quote("eur", "usd").rate!!
        assertEquals(usdLkr * eurUsd, q.rate!!, 1e-9)
        assertTrue(q.series.size >= 25)
    }

    @Test
    fun renderExchangeRateWidget(): Unit = runBlocking {
        val sizes = linkedMapOf(
            "fx-full-4x4" to DpSize(330.dp, 360.dp),
            "fx-compact-4x2" to DpSize(330.dp, 170.dp),
            "fx-tiny-4x1" to DpSize(330.dp, 76.dp),
        )
        for ((name, size) in sizes) shoot(FxWidget(), name, size)
        assertTrue(File("build/widget-shots/fx-full-4x4.png").length() > 0)
    }

    @Test
    fun renderThemes(): Unit = runBlocking {
        val settings = Settings.get(context)
        try {
            for (theme in ColorTheme.entries) {
                settings.widgetTheme = theme
                shoot(FxWidget(), "theme-${theme.key}", DpSize(330.dp, 170.dp))
            }
            settings.widgetTheme = ColorTheme.OCEAN
            settings.widgetOpacity = 50
            shoot(FxWidget(), "theme-ocean-50pct", DpSize(330.dp, 170.dp))
        } finally {
            settings.widgetTheme = null
            settings.widgetOpacity = 100
        }
    }

    private suspend fun shoot(widget: GlanceAppWidget, name: String, size: DpSize) {
        val out = File("build/widget-shots").apply { mkdirs() }
        val density = context.resources.displayMetrics.density
        val remoteViews = widget.compose(context, size = size)
        val root = FrameLayout(context)
        root.addView(remoteViews.apply(context, root))
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
        val margin = (24 * density).toInt()
        val bitmap = createBitmap(w + margin * 2, h + margin * 2)
        // Transparent margin so the shots can be composed onto any wallpaper.
        Canvas(bitmap).apply {
            translate(margin.toFloat(), margin.toFloat())
            root.draw(this)
        }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
