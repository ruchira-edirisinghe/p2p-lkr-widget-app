package dev.dfanso.lkrp2p.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.dfanso.lkrp2p.RenderSeed
import dev.dfanso.lkrp2p.core.AlertRule
import dev.dfanso.lkrp2p.core.AlertState
import dev.dfanso.lkrp2p.core.MetricsEngine
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.ThresholdDirection
import dev.dfanso.lkrp2p.core.WireFormat
import dev.dfanso.lkrp2p.data.Store
import org.junit.Before
import dev.dfanso.lkrp2p.core.FxDay
import dev.dfanso.lkrp2p.data.FxRange
import dev.dfanso.lkrp2p.data.FxRepository
import dev.dfanso.lkrp2p.render.ColorTheme
import kotlinx.coroutines.runBlocking
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders the app's screens with realistic data into app/build/app-shots/ so
 * the UI can be reviewed without a phone. The default size is a typical
 * 412x915dp phone; the README images are built from these shots by
 * docs/make_readme_images.py.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class AppScreensRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun seed() {
        val store = Store.get(app)
        RenderSeed.seed(store, days = 7)
        val now = System.currentTimeMillis() / 1000
        for (side in Side.entries) {
            val file = "lkr-${side.wire.lowercase()}-20260907.json"
            val ads = WireFormat.decodeAds(javaClass.classLoader!!.getResource(file)!!.readText())
            store.replaceSnapshot(side, MetricsEngine.bestFirst(ads, side), now - 120)
        }
        // One alert in each state the Alerts tab can show.
        val size = RenderSeed.ORDER_SIZE
        store.upsertAlert(AlertRule(side = Side.SELL, amountUsdt = size, threshold = 332.0, direction = ThresholdDirection.ABOVE), AlertState.ARMED, null)
        store.upsertAlert(AlertRule(side = Side.SELL, amountUsdt = size, threshold = 331.5, direction = ThresholdDirection.BELOW), AlertState.TRIGGERED, now - 1800)
        store.upsertAlert(AlertRule(side = Side.BUY, amountUsdt = size, threshold = 330.5, direction = ThresholdDirection.BELOW), AlertState.ARMED, null)
        store.upsertAlert(AlertRule(side = Side.BUY, amountUsdt = 1_000, threshold = 333.0, direction = ThresholdDirection.ABOVE), AlertState.ARMED, null)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Exchange rates from an offline fake, so no test touches the network.
        FxRepository.installForTests(app) { date ->
            val day = date ?: FxRepository.dayString(System.currentTimeMillis())
            val ago = ((System.currentTimeMillis() / 86_400_000L) - FxRepository.parseDay(day) / 86_400).toDouble()
            FxDay(day, mapOf("usd" to 1.0, "lkr" to 300.4 - ago * 0.05 + 0.6 * sin(ago / 4), "eur" to 0.86, "gbp" to 0.74, "aed" to 3.6725, "inr" to 88.7))
        }.also { runBlocking { it.refresh(); it.backfill(FxRange.MONTH) } }
    }

    private fun shoot(
        name: String,
        prepare: (MainViewModel) -> Unit = {},
        content: @Composable (MainViewModel, UiState) -> Unit,
    ) {
        val vm = MainViewModel(app)
        compose.setContent {
            AppTheme {
                val state by vm.state.collectAsState()
                Box(Modifier.fillMaxSize().background(Ui.Bg)) { content(vm, state) }
            }
        }
        // Let the ViewModel's IO loads land and recompose with them.
        settle()
        prepare(vm)
        settle()
        // captureToImage waits on a PixelCopy that Robolectric never delivers; draw the views directly.
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        val out = File("build/app-shots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settle() = repeat(20) {
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        compose.waitForIdle()
    }

    @Test fun rate() = shoot("rate") { vm, _ -> App(vm, startTab = Tab.RATE) }

    @Test fun ads() = shoot("ads") { vm, _ -> App(vm, startTab = Tab.ADS) }

    @Test fun alerts() = shoot("alerts") { vm, _ -> App(vm, startTab = Tab.ALERTS) }

    @Test fun settings() = shoot("settings") { vm, _ -> App(vm, startInSettings = true) }

    /** The converter working backwards from rupees. */
    @Test fun rateFromLkr() = shoot("rate-lkr", prepare = { vm ->
        vm.swapConverter()
        vm.setConverterText("100000")
    }) { vm, _ -> App(vm, startTab = Tab.RATE) }

    /** The whole scrolling Rate page in one image. */
    @Test
    @Config(qualifiers = "w412dp-h1700dp-xxhdpi")
    fun rateFullPage() = shoot("rate-full") { vm, s -> RateScreen(s, vm, onOpenSettings = {}) }

    @Test fun currencies() = shoot("currencies") { vm, _ -> App(vm, startTab = Tab.FX) }

    @Test fun rateInOceanTheme() {
        Ui.apply(app, ColorTheme.OCEAN)
        try {
            shoot("rate-ocean") { vm, _ -> App(vm, startTab = Tab.RATE) }
        } finally {
            Ui.apply(app, ColorTheme.DEFAULT)
        }
    }
}
