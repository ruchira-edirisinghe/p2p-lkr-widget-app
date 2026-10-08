package dev.dfanso.lkrp2p.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.edit
import dev.dfanso.lkrp2p.core.BinanceP2PClient
import dev.dfanso.lkrp2p.data.Poller
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Doze defers periodic WorkManager jobs for hours while the phone sits idle,
 * which left long holes in the overnight chart. An allow-while-idle alarm still
 * fires in Doze and briefly lets the app use the network, so it carries the
 * schedule; the periodic worker stays as a backstop.
 */
class CollectAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Chain the next alarm first, so a slow or failed poll cannot end the chain.
        // A reboot or an app update clears alarms; those broadcasts restart it.
        schedule(app)
        if (intent.action != null) return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(POLL_TIMEOUT_MS) {
                    Poller(
                        source = BinanceP2PClient(),
                        store = Store.get(app),
                        settings = Settings.get(app),
                        presenter = { rule, price -> AlertNotifier.present(app, rule, price) },
                    ).pollOnce(minSpacingSec = 60)
                }
                refreshEverything(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** The temporary network allowance after a while-idle alarm is short. */
        private const val POLL_TIMEOUT_MS = 25_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun intent(context: Context) = PendingIntent.getBroadcast(
            context, 0, Intent(context, CollectAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private const val PREFS = "collect-alarm"
        private const val DUE_AT = "dueAtMs"

        /** Replaces any pending alarm with one [Settings.pollMinutes] from now. */
        fun schedule(context: Context) {
            val delayMs = Settings.get(context).pollMinutes * 60_000L
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            // Inexact, so no exact-alarm permission; Doze may still nudge it by a few minutes.
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                intent(context),
            )
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong(DUE_AT, System.currentTimeMillis() + delayMs)
            }
        }

        /**
         * Starts the chain only if it is not already running, so app launches
         * and worker runs do not keep pushing the next alarm back.
         */
        fun ensureScheduled(context: Context) {
            val dueAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(DUE_AT, 0)
            // Allow some slack: Doze delivers inexact alarms late.
            val dead = System.currentTimeMillis() > dueAt + Settings.get(context).pollMinutes * 60_000L
            if (dead) schedule(context)
        }
    }
}
