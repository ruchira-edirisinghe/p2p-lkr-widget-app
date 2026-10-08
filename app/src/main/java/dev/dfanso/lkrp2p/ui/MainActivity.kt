package dev.dfanso.lkrp2p.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.data.Settings

enum class Tab(val label: String, val icon: Int) {
    RATE("P2P", R.drawable.ic_tab_rate),
    FX("Currencies", R.drawable.ic_tab_fx),
    ADS("Ads", R.drawable.ic_tab_ads),
    ALERTS("Alerts", R.drawable.ic_tab_alerts),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val fxVm: FxViewModel by viewModels()

    /** Set from a widget tap: the exchange-rate widget opens the Currencies tab. */
    private val requestedTab = mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        requestedTab.value = tabFrom(intent)
        setContent { AppTheme { App(vm, fxVm, requestedTab) } }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab.value = tabFrom(intent)
    }

    private fun tabFrom(intent: Intent?): Tab? = Tab.entries.firstOrNull { it.name == intent?.getStringExtra(EXTRA_TAB) }

    companion object {
        const val EXTRA_TAB = "tab"
    }

    override fun onStart() {
        super.onStart()
        // The wallpaper theme follows the wallpaper, which may have changed since.
        Ui.apply(this, Settings.get(this).appTheme)
        // Opening the app is when people expect the rate to be current.
        vm.refreshIfOld()
    }
}

/** [startTab] and [startInSettings] let the screenshot tests open any screen. */
@Composable
internal fun App(
    vm: MainViewModel,
    fxVm: FxViewModel = viewModel(),
    requested: MutableState<Tab?> = remember { mutableStateOf(null) },
    startTab: Tab = Tab.RATE,
    startInSettings: Boolean = false,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val fxState by fxVm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var settingsOpen by rememberSaveable { mutableStateOf(startInSettings) }
    LaunchedEffect(requested.value) {
        requested.value?.let { tab = it; settingsOpen = false }
        requested.value = null
    }
    BackHandler(enabled = settingsOpen || tab != Tab.RATE) {
        if (settingsOpen) settingsOpen = false else tab = Tab.RATE
    }

    Scaffold(
        containerColor = Ui.Bg,
        bottomBar = {
            if (!settingsOpen) {
                NavigationBar(containerColor = Ui.Surface) {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(painterResource(item.icon), null) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Ui.Up,
                                selectedTextColor = Ui.Up,
                                indicatorColor = Ui.Up.copy(alpha = 0.14f),
                                unselectedIconColor = Ui.Dim,
                                unselectedTextColor = Ui.Dim,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Tablets and landscape: keep a readable column instead of stretching edge to edge.
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 640.dp)) {
                when {
                    settingsOpen -> SettingsScreen(state, vm, onBack = { settingsOpen = false })
                    tab == Tab.RATE -> RateScreen(state, vm, onOpenSettings = { settingsOpen = true })
                    tab == Tab.FX -> CurrenciesScreen(fxState, fxVm, onOpenSettings = { settingsOpen = true })
                    tab == Tab.ADS -> AdsScreen(state, vm)
                    tab == Tab.ALERTS -> AlertsScreen(state, vm)
                }
            }
        }
    }
}
