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
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.dfanso.lkrp2p.RenderSeed
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

/**
 * Renders the real widget at common home-screen sizes and states into
 * app/build/widget-shots/ so layout changes can be checked by eye. The
 * README images are built from these by docs/make_readme_images.py.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: P2PApp schedules WorkManager, which Robolectric does not initialise.
@Config(sdk = [35], qualifiers = "xxhdpi", application = Application::class)
class WidgetRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun seedAMonthOfHistory() = RenderSeed.seed(Store.get(context))

    @Test
    fun renderCommonSizes() = runBlocking {
        val shots = linkedMapOf(
            "full-4x4" to Shot(DpSize(330.dp, 360.dp)),
            "full-4x4-week" to Shot(DpSize(330.dp, 360.dp), window = "7d"),
            "full-4x4-month" to Shot(DpSize(330.dp, 360.dp), window = "30d"),
            "full-4x4-buy" to Shot(DpSize(330.dp, 360.dp), side = "BUY"),
            "full-narrow-tall" to Shot(DpSize(270.dp, 430.dp)),
            "compact-4x2" to Shot(DpSize(330.dp, 170.dp)),
            "compact-3x2" to Shot(DpSize(240.dp, 150.dp)),
            "tiny-4x1" to Shot(DpSize(330.dp, 76.dp)),
            "tiny-2x1" to Shot(DpSize(150.dp, 70.dp)),
        )
        shots.forEach { (name, shot) -> render(name, shot) }
        assertTrue(File(out, "full-4x4.png").length() > 0)
    }

    @Test
    fun renderStale() = runBlocking {
        // Nothing collected for three hours: the phone was offline or asleep.
        RenderSeed.seed(Store.get(context), days = 2, endsSecAgo = 3 * 3600)
        render("compact-4x2-stale", Shot(DpSize(330.dp, 170.dp)))
    }

    private class Shot(val size: DpSize, val side: String? = null, val window: String? = null)

    private val out = File("build/widget-shots").apply { mkdirs() }

    private suspend fun render(name: String, shot: Shot) {
        val size = shot.size
        val state = mutablePreferencesOf().apply {
            shot.side?.let { this[RateWidget.SideKey] = it }
            shot.window?.let { this[RateWidget.WindowKey] = it }
        }
        val density = context.resources.displayMetrics.density
        val remoteViews = RateWidget().compose(context, size = size, state = state)
        val root = FrameLayout(context)
        val view = remoteViews.apply(context, root)
        root.addView(view)
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)

        val margin = (24 * density).toInt()
        // Transparent margin so the shots can be composed onto any wallpaper.
        val bitmap = createBitmap(w + margin * 2, h + margin * 2)
        Canvas(bitmap).apply {
            translate(margin.toFloat(), margin.toFloat())
            root.draw(this)
        }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
