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
    private const val SUMMARY_CHANNEL = "daily-summary"
    private const val SUMMARY_ID = 1002

    /** The summary goes out with the first poll at or after this local hour. */
    const val SUMMARY_HOUR = 9

    fun createChannels(context: Context) {
        val status = NotificationChannel(STATUS_CHANNEL, "Rate in notification bar", NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = "The current sell rate, kept in the notification bar while you have it switched on"
                setShowBadge(false)
            }
        val summary = NotificationChannel(SUMMARY_CHANNEL, "Daily summary", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Each morning: the last 24 hours' low, high and average sell rate" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(status, summary))
    }

    /** Called after every poll, wherever it ran. */
    fun afterPoll(context: Context) {
        val settings = Settings.get(context)
        if (settings.statusNotification) showStatus(context, settings) else cancelStatus(context)
        if (settings.dailySummary) maybeSendSummary(context, settings)
    }

    /**
     * No scheduler of its own: background polls run every 15 to 60 minutes
     * anyway, so the first one after [SUMMARY_HOUR] sends it, once a day.
     */
    private fun maybeSendSummary(context: Context, settings: Settings) {
        val now = java.util.Calendar.getInstance()
        if (now.get(java.util.Calendar.HOUR_OF_DAY) < SUMMARY_HOUR) return
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(now.time)
        if (settings.lastSummaryDay == today || !notificationsGranted(context)) return
        val data = RateSnapshot.load(context, settings.side, ChartWindow.DAY)
        val price = data.price ?: return
        val prices = data.series.map { it.price }
        if (prices.isEmpty()) return
        val notification = NotificationCompat.Builder(context, SUMMARY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rate)
            .setContentTitle("USDT/LKR sell rate: Rs ${Formatting.price(price)}${data.trend?.let { " · ${it.arrow()}" }.orEmpty()}")
            .setContentText("Last 24h: low ${Formatting.price(prices.min())} · high ${Formatting.price(prices.max())} · average ${Formatting.price(prices.average())}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(
                        "Last 24 hours: low ${Formatting.price(prices.min())}, high ${Formatting.price(prices.max())}, " +
                            "average ${Formatting.price(prices.average())}.",
                        data.trend?.let { "Change: ${Formatting.signedDelta(it.delta)} LKR (${Formatting.percent(it.percent)})." },
                        data.premiumPercent?.let { "${Formatting.percent(it)} vs the official USD/LKR rate." },
                    ).joinToString("\n")
                )
            )
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(SUMMARY_ID, notification)
        settings.lastSummaryDay = today
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
