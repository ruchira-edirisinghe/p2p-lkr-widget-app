package dev.dfanso.lkrp2p.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.dfanso.lkrp2p.core.BinanceP2PClient
import dev.dfanso.lkrp2p.data.PollOutcome
import dev.dfanso.lkrp2p.data.Poller
import dev.dfanso.lkrp2p.data.Settings
import dev.dfanso.lkrp2p.data.Store
import dev.dfanso.lkrp2p.data.FxRepository
import dev.dfanso.lkrp2p.widget.Widgets
import java.util.concurrent.TimeUnit

/** One polling cycle, run by WorkManager every [Settings.pollMinutes] or on demand. */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val poller = Poller(
            source = BinanceP2PClient(),
            store = Store.get(context),
            settings = Settings.get(context),
            presenter = { rule, price -> AlertNotifier.present(context, rule, price) },
        )
        val oneShot = tags.contains(TAG_ONE_SHOT)
        // The worker survives reboots and force-stops that drop alarms; revive the alarm chain.
        CollectAlarm.ensureScheduled(context)
        // A scheduled run that lands just after the app refreshed has nothing to add.
        val outcomes = poller.pollOnce(minSpacingSec = if (oneShot) 0 else 60)
        // Repaint widgets either way: on failure they show the stale badge.
        refreshEverything(context)

        // Periodic work just waits for its next slot. A user-requested refresh
        // that failed entirely is retried with backoff instead.
        val allFailed = outcomes.all { it is PollOutcome.Failed }
        return if (allFailed && oneShot && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "collect-periodic"
        private const val ONE_SHOT = "collect-now"
        private const val TAG_ONE_SHOT = "one-shot"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Android will not run periodic work more often than every 15 minutes,
         * and Doze may stretch that further while the phone is idle.
         */
        fun schedule(context: Context, replace: Boolean = false) {
            val minutes = Settings.get(context).pollMinutes.toLong()
            val request = PeriodicWorkRequestBuilder<CollectWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            if (replace) CollectAlarm.schedule(context) else CollectAlarm.ensureScheduled(context)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CollectWorker>()
                .setConstraints(network)
                .addTag(TAG_ONE_SHOT)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * After a P2P poll: top up the daily reference rates when they are a few hours
 * old (they change once a day), then repaint every widget of both kinds.
 */
internal suspend fun refreshEverything(context: Context) {
    FxRepository.get(context).refreshIfDue()
    Widgets.refreshAll(context)
}
