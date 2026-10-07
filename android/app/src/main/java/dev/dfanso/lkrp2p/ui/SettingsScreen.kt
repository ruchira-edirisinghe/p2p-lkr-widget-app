package dev.dfanso.lkrp2p.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.PaymentMethod
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.widget.RateWidgetReceiver

private enum class Dialog { ORDER_SIZE, PAYMENT, FREQUENCY, WIDGET_SIDE }

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
                Text("Not affiliated with Binance. Not financial advice.", color = Ui.Dim, fontSize = 14.sp)
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
        Dialog.WIDGET_SIDE -> ChoiceDialog("New widgets show", Side.entries, state.defaultSide, { "${it.label} rate" }, { dialog = null }) {
            vm.setDefaultSide(it); dialog = null
        }
        null -> Unit
    }
}

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
