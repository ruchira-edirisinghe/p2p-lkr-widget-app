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
import dev.dfanso.lkrp2p.core.ThresholdDirection
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

    fun present(context: Context, rule: AlertRule, price: Double) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val verb = if (rule.direction == ThresholdDirection.ABOVE) "rose above" else "fell below"
        val title = "${rule.side.label} rate $verb ${Formatting.price(rule.threshold)}"
        val text = "Now ${Formatting.price(price)} LKR per USDT for ${Formatting.usdt(rule.amountUsdt.toDouble())}"
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
        NotificationManagerCompat.from(context).notify(rule.id.hashCode(), notification)
    }
}
