package dev.dfanso.lkrp2p.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.AlertRule
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.MoveAlertRule
import dev.dfanso.lkrp2p.core.MoveDirection
import dev.dfanso.lkrp2p.core.ThresholdDirection
import dev.dfanso.lkrp2p.data.AlertPresenter
import dev.dfanso.lkrp2p.ui.MainActivity

object AlertNotifier {
    private const val CHANNEL = "rate-alerts"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.alert_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** What the poller calls when an alert of either kind fires. */
    fun presenter(context: Context) = object : AlertPresenter {
        override fun present(rule: AlertRule, price: Double) = AlertNotifier.present(context, rule, price)
        override fun presentMove(rule: MoveAlertRule, price: Double, changePercent: Double) =
            AlertNotifier.presentMove(context, rule, price, changePercent)
    }

    fun present(context: Context, rule: AlertRule, price: Double) {
        val verb = if (rule.direction == ThresholdDirection.ABOVE) "rose above" else "fell below"
        notify(
            context, rule.id.hashCode(),
            title = "Sell rate $verb ${Formatting.price(rule.threshold)}",
            text = "Now ${Formatting.price(price)} LKR per USDT for ${Formatting.usdt(rule.amountUsdt.toDouble())}",
        )
    }

    fun presentMove(context: Context, rule: MoveAlertRule, price: Double, changePercent: Double) {
        val verb = when {
            rule.direction == MoveDirection.EITHER && changePercent < 0 -> "fell"
            rule.direction == MoveDirection.EITHER -> "rose"
            rule.direction == MoveDirection.UP -> "rose"
            else -> "fell"
        }
        notify(
            context, rule.id.hashCode(),
            title = "Sell rate $verb ${Formatting.percent(changePercent).trimStart('+', '-')} in ${rule.hours}h",
            text = "Now ${Formatting.price(price)} LKR per USDT for ${Formatting.usdt(rule.amountUsdt.toDouble())}",
        )
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rate)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
