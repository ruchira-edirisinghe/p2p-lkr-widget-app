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
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.Sample
import dev.dfanso.lkrp2p.data.Store
import kotlinx.coroutines.runBlocking
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
 * Renders the real widget at common home-screen sizes into
 * app/build/widget-shots/ so layout changes can be checked by eye.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: P2PApp schedules WorkManager, which Robolectric does not initialise.
@Config(sdk = [35], qualifiers = "xxhdpi", application = Application::class)
class WidgetRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun seedAWeekOfHistory() {
        val store = Store.get(context)
        // Store is a process-wide singleton; other render tests seed it too.
        store.clearForTests()
        val now = System.currentTimeMillis() / 1000
        for (i in 0 until 12 * 24 * 7) {
            val ts = now - i * 900L
            // i counts back in time: a slow swell plus intraday wobble, ending on a rise.
            val price = 331.2 - 1.1 * sin(i / 55.0) + 0.35 * sin(i / 6.0) - i * 0.0008
            for (side in Side.entries) {
                val p = if (side == Side.SELL) price else price + 0.8
                store.append(Sample(ts, side, 500, p, p + 1, p + 0.2, "Trader", 700.0, 1000.0, 300000.0))
            }
        }
    }

    @Test
    fun renderCommonSizes() = runBlocking {
        val sizes = linkedMapOf(
            "full-4x4" to DpSize(330.dp, 360.dp),
            "full-narrow-tall" to DpSize(270.dp, 430.dp),
            "compact-4x2" to DpSize(330.dp, 170.dp),
            "compact-3x2" to DpSize(240.dp, 150.dp),
            "tiny-4x1" to DpSize(330.dp, 76.dp),
            "tiny-2x1" to DpSize(150.dp, 70.dp),
        )
        val out = File("build/widget-shots").apply { mkdirs() }
        val density = context.resources.displayMetrics.density
        for ((name, size) in sizes) {
            val remoteViews = RateWidget().compose(context, size = size)
            val root = FrameLayout(context)
            val view = remoteViews.apply(context, root)
            root.addView(view)
            val w = (size.width.value * density).toInt()
            val h = (size.height.value * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)

            val margin = (24 * density).toInt()
            val bitmap = createBitmap(w + margin * 2, h + margin * 2)
            Canvas(bitmap).apply {
                drawColor(0xFF222222.toInt())
                translate(margin.toFloat(), margin.toFloat())
                root.draw(this)
            }
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(File(out, "full-4x4.png").length() > 0)
    }
}
