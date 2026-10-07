package dev.dfanso.lkrp2p.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.dfanso.lkrp2p.core.AlertRule
import dev.dfanso.lkrp2p.core.AlertState
import dev.dfanso.lkrp2p.core.MetricsEngine
import dev.dfanso.lkrp2p.core.Sample
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.ThresholdDirection
import dev.dfanso.lkrp2p.core.WireFormat
import dev.dfanso.lkrp2p.data.Store
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.math.sin

/**
 * Renders each app screen with realistic data into app/build/app-shots/ so
 * the UI can be reviewed without a phone. Tall screen so scrolling content fits.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1450dp-xxhdpi", application = Application::class)
class AppScreensRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun seed() {
        val store = Store.get(app)
        store.clearForTests()
        val now = System.currentTimeMillis() / 1000
        for (i in 0 until 4 * 24 * 7) {
            val price = 335.2 - 1.1 * sin(i / 18.0) + 0.35 * sin(i / 2.0) - i * 0.003
            store.append(Sample(now - i * 900L, Side.SELL, 500, price, price + 1, price + 0.2, "Trader", 700.0, 1e4, 3e5))
            store.append(Sample(now - i * 900L, Side.BUY, 500, price + 0.9, price + 0.5, price + 1, "Trader", 700.0, 1e4, 3e5))
        }
        val ads = WireFormat.decodeAds(javaClass.classLoader!!.getResource("lkr-sell-20260907.json")!!.readText())
        store.replaceSnapshot(Side.SELL, MetricsEngine.bestFirst(ads, Side.SELL), now - 120)
        store.upsertAlert(AlertRule(side = Side.SELL, amountUsdt = 500, threshold = 337.0, direction = ThresholdDirection.ABOVE), AlertState.ARMED, null)
        store.upsertAlert(AlertRule(side = Side.BUY, amountUsdt = 500, threshold = 330.0, direction = ThresholdDirection.BELOW), AlertState.ARMED, null)
    }

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable (MainViewModel, UiState) -> Unit) {
        val vm = MainViewModel(app)
        compose.setContent {
            AppTheme {
                val state by vm.state.collectAsState()
                Box(Modifier.fillMaxSize().background(Ui.Bg)) { content(vm, state) }
            }
        }
        // Let the ViewModel's IO loads land and recompose with them.
        repeat(20) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
            compose.waitForIdle()
        }
        // captureToImage waits on a PixelCopy that Robolectric never delivers; draw the views directly.
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        val out = File("build/app-shots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun rate() = shoot("rate") { vm, s -> RateScreen(s, vm, onOpenSettings = {}) }

    @Test fun ads() = shoot("ads") { vm, s -> AdsScreen(s, vm) }

    @Test fun alerts() = shoot("alerts") { vm, s -> AlertsScreen(s, vm) }

    @Test fun settings() = shoot("settings") { vm, s -> SettingsScreen(s, vm, onBack = {}) }
}
