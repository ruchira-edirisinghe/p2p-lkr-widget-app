package dev.dfanso.lkrp2p.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.dfanso.lkrp2p.data.Store
import dev.dfanso.lkrp2p.widget.Widgets
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.PaymentMethod
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.widget.RateWidgetReceiver

private enum class Dialog { ORDER_SIZE, PAYMENT, FREQUENCY, WIDGET_SIDE, OPACITY }

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Ui.Text) }
            Text("Settings", color = Ui.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Appearance", Modifier.padding(start = 4.dp))
            val settings = remember { Settings.get(context) }
            var appTheme by remember { mutableStateOf(settings.appTheme) }
            var widgetTheme by remember { mutableStateOf(settings.widgetTheme) }
            val scope = rememberCoroutineScope()
            fun repaintWidgets() = scope.launch { Widgets.refreshAll(context) }
            Panel {
                Text("App theme", color = Ui.Text, fontSize = 16.sp)
                ThemeSwatches(appTheme, { picked ->
                    val theme = picked ?: return@ThemeSwatches
                    appTheme = theme
                    settings.appTheme = theme
                    Ui.apply(context, theme)
                    if (widgetTheme == null) repaintWidgets()
                })
                Text("Widget theme", color = Ui.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                ThemeSwatches(widgetTheme, { picked ->
                    widgetTheme = picked
                    settings.widgetTheme = picked
                    repaintWidgets()
                }, followLabel = "Same as app")
                Text(
                    "Applies to every widget that has no theme of its own. To theme one widget, long-press it " +
                        "and choose Settings (or Reconfigure).",
                    color = Ui.Dim, fontSize = 13.sp,
                )
            }
            Panel(padding = 8.dp) {
                SettingRow(
                    "Widget background",
                    if (state.widgetOpacity == 0) "Transparent" else "${state.widgetOpacity}%",
                    "Lower it to let your wallpaper show through.",
                ) { dialog = Dialog.OPACITY }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Rate", Modifier.padding(start = 4.dp))
            Panel(padding = 8.dp) {
                SettingRow(
                    "Order size",
                    Formatting.usdt(state.orderSize.toDouble()),
                    "Only ads that accept an order this big count towards the rate. Most ads have a minimum.",
                ) { dialog = Dialog.ORDER_SIZE }
                SettingRow("Payment method", state.payment.displayName.substringBefore(" (")) { dialog = Dialog.PAYMENT }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Updates", Modifier.padding(start = 4.dp))
            Panel(padding = 8.dp) {
                SettingRow(
                    "Check for a new rate",
                    "Every ${state.pollMinutes} min",
                    "Runs in the background with no notification. Battery saver can delay it while the phone is idle.",
                ) { dialog = Dialog.FREQUENCY }
                // Re-read on return from system settings.
                var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = isUnrestricted(context) }
                SettingRow(
                    "Battery use",
                    if (unrestricted) "Unrestricted" else "Optimised",
                    if (unrestricted) null
                    else "The phone may pause updates for hours while it sleeps, leaving gaps in the chart. " +
                        "Tap, then set Battery to Unrestricted.",
                ) {
                    context.startActivity(
                        Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Widget", Modifier.padding(start = 4.dp))
            Panel(padding = 8.dp) {
                SettingRow(
                    "New widgets show",
                    "${state.defaultSide.label} rate",
                    "Each widget can switch on its own with its ↓↑ button.",
                ) { dialog = Dialog.WIDGET_SIDE }
                val manager = AppWidgetManager.getInstance(context)
                if (manager.isRequestPinAppWidgetSupported) {
                    SettingRow("Add a widget to the home screen", "Add") {
                        manager.requestPinAppWidget(ComponentName(context, RateWidgetReceiver::class.java), null, null)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("About", Modifier.padding(start = 4.dp))
            Panel {
                Text(
                    "Rates come from the public Binance P2P order book. History is kept on this phone for 30 days " +
                        "and is never uploaded. No account, no ads, no tracking.",
                    color = Ui.Dim, fontSize = 14.sp,
                )
                Text(
                    "Exchange rates for the Currencies tab and widget come from the open-source " +
                        "fawazahmed0/exchange-api and are daily reference rates.",
                    color = Ui.Dim, fontSize = 14.sp,
                )
                Text("Not affiliated with Binance. Not financial advice.", color = Ui.Dim, fontSize = 14.sp)
            }
            Panel(padding = 8.dp) {
                SettingRow("Open source", "GitHub", "Free and open source under the MIT License. Read the code, report a bug or contribute.") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                }
                val scope = rememberCoroutineScope()
                SettingRow("Export P2P history", "CSV", "Every saved sample from the last 30 days, to share or open in a spreadsheet.") {
                    scope.launch {
                        val csv = withContext(Dispatchers.IO) { Store.get(context).exportCsv() }
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/csv")
                            .putExtra(Intent.EXTRA_SUBJECT, "USDT/LKR P2P history")
                            .putExtra(Intent.EXTRA_TEXT, csv)
                        context.startActivity(Intent.createChooser(send, "Export history"))
                    }
                }
            }
        }
    }

    when (dialog) {
        Dialog.ORDER_SIZE -> OrderSizeDialog(state.orderSize, onDismiss = { dialog = null }) {
            vm.setOrderSize(it); dialog = null
        }
        Dialog.PAYMENT -> ChoiceDialog("Payment method", PaymentMethod.entries, state.payment, { it.displayName }, { dialog = null }) {
            vm.setPayment(it); dialog = null
        }
        Dialog.FREQUENCY -> ChoiceDialog("Check for a new rate", Settings.Defaults.POLL_CHOICES, state.pollMinutes, { "Every $it minutes" }, { dialog = null }) {
            vm.setPollMinutes(it); dialog = null
        }
        Dialog.OPACITY -> ChoiceDialog(
            "Widget background", Settings.Defaults.OPACITY_CHOICES, state.widgetOpacity,
            { if (it == 0) "Transparent" else "$it% opaque" }, { dialog = null },
        ) {
            vm.setWidgetOpacity(it); dialog = null
        }
        Dialog.WIDGET_SIDE -> ChoiceDialog("New widgets show", Side.entries, state.defaultSide, { "${it.label} rate" }, { dialog = null }) {
            vm.setDefaultSide(it); dialog = null
        }
        null -> Unit
    }
}

const val SOURCE_URL = "https://github.com/ruchira-edirisinghe/p2p-lkr-widget-app"

private fun isUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(option) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Text(label(option), fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun OrderSizeDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val amount = text.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Set this to roughly how much you usually trade. The chart starts a fresh history for each size.",
                    fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.Defaults.ORDER_SIZE_CHOICES.forEach { size ->
                        TextButton(onClick = { text = size.toString() }) { Text(Formatting.whole(size)) }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(7) },
                    label = { Text("USDT") },
                    singleLine = true,
                    isError = amount == null,
                    supportingText = { if (amount == null) Text("Enter a whole number above 0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton(enabled = amount != null, onClick = { amount?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
