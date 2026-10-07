package dev.dfanso.lkrp2p.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.core.content.ContextCompat
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.AlertState
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.Side
import dev.dfanso.lkrp2p.core.ThresholdDirection
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(notificationsGranted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Alerts", color = Ui.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Get a notification when the rate reaches your target. Each alert fires once, " +
                            "then re-arms after the rate moves back.",
                        color = Ui.Dim, fontSize = 14.sp,
                    )
                }
            }
            if (state.alerts.isNotEmpty() && !notificationsAllowed) {
                item {
                    Panel {
                        Text("Notifications are off", color = Ui.Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Your alerts can't reach you until you allow notifications.", color = Ui.Dim, fontSize = 14.sp)
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else context.startActivity(
                                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }) { Text("Allow notifications") }
                    }
                }
            }
            if (state.alerts.isEmpty()) {
                item { EmptyAlerts(state) }
            }
            items(state.alerts, key = { it.rule.id }) { alert -> AlertCard(alert, state, onDelete = { vm.deleteAlert(alert.rule.id) }) }
        }

        ExtendedFloatingActionButton(
            onClick = { sheetOpen = true },
            icon = { Icon(painterResource(R.drawable.ic_add), null) },
            text = { Text("New alert") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (sheetOpen) {
        NewAlertSheet(
            state = state,
            onDismiss = { sheetOpen = false },
            onCreate = { side, direction, threshold ->
                vm.addAlert(side, threshold, direction)
                sheetOpen = false
                if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
}

private fun notificationsGranted(context: android.content.Context) =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun EmptyAlerts(state: UiState) {
    val now = state.latest[Side.SELL]?.fillablePrice
    Panel(padding = 20.dp) {
        Text("No alerts yet", color = Ui.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (now != null) {
                "For example: tell me when the sell rate rises to ${Formatting.price(kotlin.math.ceil(now + 1))}. " +
                    "Tap New alert to set one."
            } else {
                "Tap New alert to set a target rate."
            },
            color = Ui.Dim, fontSize = 14.sp,
        )
    }
}

@Composable
private fun AlertCard(alert: AlertView, state: UiState, onDelete: () -> Unit) {
    val rule = alert.rule
    val now = state.latest[rule.side]?.fillablePrice
    val rising = rule.direction == ThresholdDirection.ABOVE
    val triggered = alert.state == AlertState.TRIGGERED
    val otherSize = rule.amountUsdt != state.orderSize
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${rule.side.label} rate ${if (rising) "rises to" else "falls to"} ${Formatting.price(rule.threshold)}",
                    color = Ui.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        otherSize -> "Set for ${Formatting.usdt(rule.amountUsdt.toDouble())} orders. Paused until you switch back to that order size."
                        now == null -> "Waiting for the first rate"
                        triggered -> "Reached. Now ${Formatting.price(now)}. Will alert again after it crosses back."
                        else -> "Now ${Formatting.price(now)} · ${Formatting.price(abs(rule.threshold - now))} to go"
                    },
                    color = Ui.Dim, fontSize = 13.sp,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), "Delete alert", tint = Ui.Dim)
            }
        }
        val (label, color) = when {
            otherSize -> "Paused" to Ui.Faint
            triggered -> "Reached" to Ui.Up
            else -> "Watching" to Ui.Dim
        }
        Text(
            label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAlertSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onCreate: (Side, ThresholdDirection, Double) -> Unit,
) {
    var side by rememberSaveable { mutableStateOf(state.side) }
    var direction by rememberSaveable { mutableStateOf(ThresholdDirection.ABOVE) }
    val current = state.latest[side]?.fillablePrice
    var text by rememberSaveable { mutableStateOf("") }
    // Start from the live rate, nudged in the chosen direction, so the common
    // case is one tap.
    LaunchedEffect(side, direction, current) {
        if (current != null) {
            val target = if (direction == ThresholdDirection.ABOVE) kotlin.math.ceil(current + 0.5) else kotlin.math.floor(current - 0.5)
            text = "%.2f".format(java.util.Locale.US, target)
        }
    }
    val threshold = text.toDoubleOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ui.Surface) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("New alert", color = Ui.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            SectionLabel("Which rate")
            Segmented(Side.entries, side, { if (it == Side.SELL) "Sell rate" else "Buy rate" }, { side = it })
            SectionLabel("Alert me when it")
            Segmented(ThresholdDirection.entries, direction, { if (it == ThresholdDirection.ABOVE) "Rises to" else "Falls to" }, { direction = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { threshold?.let { text = "%.2f".format(java.util.Locale.US, it - 1) } }) { Text("−1") }
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' }.take(9) },
                    label = { Text("LKR per USDT") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { threshold?.let { text = "%.2f".format(java.util.Locale.US, it + 1) } }) { Text("+1") }
            }
            Text(
                current?.let { "The ${side.label.lowercase()} rate is ${Formatting.price(it)} now." } ?: "No rate loaded yet.",
                color = Ui.Dim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = threshold != null && threshold > 0,
                onClick = { threshold?.let { onCreate(side, direction, it) } },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Create alert", fontSize = 16.sp) }
        }
    }
}
