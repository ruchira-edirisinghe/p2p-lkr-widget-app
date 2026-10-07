package dev.dfanso.lkrp2p.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dfanso.lkrp2p.R

enum class Tab(val label: String, val icon: Int) {
    RATE("Rate", R.drawable.ic_tab_rate),
    ADS("Ads", R.drawable.ic_tab_ads),
    ALERTS("Alerts", R.drawable.ic_tab_alerts),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { AppTheme { App(vm) } }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is when people expect the rate to be current.
        vm.refreshIfOld()
    }
}

/** [startTab] and [startInSettings] let the screenshot tests open any screen. */
@Composable
internal fun App(vm: MainViewModel, startTab: Tab = Tab.RATE, startInSettings: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var settingsOpen by rememberSaveable { mutableStateOf(startInSettings) }
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
                    tab == Tab.ADS -> AdsScreen(state, vm)
                    tab == Tab.ALERTS -> AlertsScreen(state, vm)
                }
            }
        }
    }
}
