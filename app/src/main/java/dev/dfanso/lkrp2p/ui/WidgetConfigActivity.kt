package dev.dfanso.lkrp2p.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.data.FxRepository
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.render.ColorTheme
import dev.dfanso.lkrp2p.widget.FxWidget
import dev.dfanso.lkrp2p.widget.FxWidgetReceiver
import dev.dfanso.lkrp2p.widget.RateWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown when a widget is added (before Android 12) or reconfigured from its
 * long-press menu: the theme for that one widget and, for exchange-rate
 * widgets, which pair it shows.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Backing out of the first configuration removes the widget, as Android expects.
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className
        val isFx = provider == FxWidgetReceiver::class.java.name
        val settings = Settings.get(this)
        Ui.apply(this, settings.appTheme)

        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(widgetId)
            val widget = if (isFx) FxWidget() else RateWidget()
            val prefs: Preferences = getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
            val currencies = withContext(Dispatchers.IO) {
                FxRepository.get(this@WidgetConfigActivity).currencies()
            }

            setContent {
                AppTheme {
                    var from by remember { mutableStateOf(prefs[FxWidget.FromKey] ?: settings.fxFrom) }
                    var to by remember { mutableStateOf(prefs[FxWidget.ToKey] ?: settings.fxTo) }
                    var theme by remember { mutableStateOf(ColorTheme.fromKey(prefs[RateWidget.ThemeKey])) }
                    var picking by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()

                    Surface(color = Ui.Bg, modifier = Modifier.fillMaxSize()) {
                        Column(
                            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Text(
                                if (isFx) "Exchange rate widget" else "P2P rate widget",
                                color = Ui.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            )
                            if (isFx) {
                                Panel {
                                    SectionLabel("Currency pair")
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CurrencyChip(from, { picking = "from" })
                                        IconButton(onClick = { val f = from; from = to; to = f }) {
                                            Icon(painterResource(R.drawable.ic_swap), "Swap", tint = Ui.Text)
                                        }
                                        CurrencyChip(to, { picking = "to" })
                                    }
                                    Text(
                                        "Shows how many ${to.uppercase()} one ${from.uppercase()} buys. Tap the swap " +
                                            "arrows on the widget to flip it.",
                                        color = Ui.Dim, fontSize = 13.sp,
                                    )
                                }
                            }
                            Panel {
                                SectionLabel("Theme for this widget")
                                ThemeSwatches(theme, { theme = it }, followLabel = "Default")
                                Text(
                                    "Default uses the widget theme from the app's Settings, so changing it there " +
                                        "updates every widget at once.",
                                    color = Ui.Dim, fontSize = 13.sp,
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        updateAppWidgetState(this@WidgetConfigActivity, glanceId) { p ->
                                            if (isFx) {
                                                p[FxWidget.FromKey] = from
                                                p[FxWidget.ToKey] = to
                                            }
                                            if (theme == null) p.remove(RateWidget.ThemeKey)
                                            else p[RateWidget.ThemeKey] = theme!!.key
                                        }
                                        widget.update(this@WidgetConfigActivity, glanceId)
                                        setResult(RESULT_OK, result)
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("Save widget", fontSize = 16.sp) }
                        }
                    }

                    picking?.let { which ->
                        CurrencyPickerDialog(
                            title = if (which == "from") "From currency" else "To currency",
                            currencies = currencies,
                            selected = if (which == "from") from else to,
                            onDismiss = { picking = null },
                        ) { code ->
                            if (which == "from") from = code else to = code
                            picking = null
                        }
                    }
                }
            }
        }
    }
}
