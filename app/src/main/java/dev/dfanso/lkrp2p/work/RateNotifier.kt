package dev.dfanso.lkrp2p.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dfanso.lkrp2p.R
import dev.dfanso.lkrp2p.core.ChartWindow
import dev.dfanso.lkrp2p.core.Formatting
import dev.dfanso.lkrp2p.core.Trend
import dev.dfanso.lkrp2p.data.RateSnapshot
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.ui.MainActivity
import dev.dfanso.lkrp2p.ui.notificationsGranted
import java.text.DateFormat
import java.util.Date

/** The optional notifications that carry the rate itself, rather than an alert. */
object RateNotifier {
    private const val STATUS_CHANNEL = "rate-status"
    private const val STATUS_ID = 1001

    fun createChannels(context: Context) {
        val status = NotificationChannel(STATUS_CHANNEL, "Rate in notification bar", NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = "The current sell rate, kept in the notification bar while you have it switched on"
                setShowBadge(false)
            }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(status)
    }

    /** Called after every poll, wherever it ran. */
    fun afterPoll(context: Context) {
        val settings = Settings.get(context)
        if (settings.statusNotification) showStatus(context, settings) else cancelStatus(context)
    }

    fun cancelStatus(context: Context) = NotificationManagerCompat.from(context).cancel(STATUS_ID)

    private fun showStatus(context: Context, settings: Settings) {
        if (!notificationsGranted(context)) return
        val data = RateSnapshot.load(context, settings.side, ChartWindow.DAY)
        val price = data.price ?: return
        val time = data.sample?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timestampSec * 1000)) }
        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rate)
            .setContentTitle("Rs ${Formatting.price(price)} per USDT${data.trend?.let { " · ${it.arrow()} 24h" }.orEmpty()}")
            .setContentText(
                listOfNotNull(
                    "Sell · Sri Lanka bank transfer",
                    data.premiumPercent?.let { "${Formatting.percent(it)} vs official" },
                    time?.let { if (data.isStale) "stale since $it" else "at $it" },
                ).joinToString(" · ")
            )
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(STATUS_ID, notification)
    }

    private fun Trend.arrow(): String {
        val symbol = when (direction) {
            Trend.Direction.UP -> "▲"
            Trend.Direction.DOWN -> "▼"
            Trend.Direction.FLAT -> "■"
        }
        return "$symbol ${Formatting.percent(percent).trimStart('+', '-')}"
    }

    internal fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
