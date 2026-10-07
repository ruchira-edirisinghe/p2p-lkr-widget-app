package dev.dfanso.lkrp2p.data

import dev.dfanso.lkrp2p.core.AdSource
import dev.dfanso.lkrp2p.core.AlertEngine
import dev.dfanso.lkrp2p.core.AlertRule
import dev.dfanso.lkrp2p.core.AlertState
import dev.dfanso.lkrp2p.core.MetricsEngine
import dev.dfanso.lkrp2p.core.P2PError
import dev.dfanso.lkrp2p.core.Sample
import dev.dfanso.lkrp2p.core.Side
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PollOutcome {
    data class Stored(val sample: Sample) : PollOutcome
    data class Failed(val side: Side, val error: P2PError) : PollOutcome

    /** Another caller polled moments ago; nothing was fetched. */
    data class Skipped(val side: Side) : PollOutcome
}

fun interface AlertPresenter {
    fun present(rule: AlertRule, price: Double)
}

/**
 * Owns one polling cycle: fetch, compute, persist, alert. Both sides are
 * collected every cycle so any widget can flip between Sell and Buy and still
 * have history to chart.
 */
class Poller(
    private val source: AdSource,
    private val store: Store,
    private val settings: Settings,
    private val presenter: AlertPresenter,
    private val retryDelayMs: Long = 2_000,
) {
    /**
     * The background chain, the safety-net job and the open app can all ask for
     * a poll; [gate] serialises them, and [minSpacingSec] turns a poll that
     * lands right after another one into a no-op instead of a duplicate sample.
     */
    suspend fun pollOnce(minSpacingSec: Long = 0): List<PollOutcome> = gate.withLock {
        val nowSec = System.currentTimeMillis() / 1000
        val outcomes = Side.entries.map { side ->
            val latest = store.latest(side, settings.amountUsdt)
            if (minSpacingSec > 0 && latest != null && nowSec - latest.timestampSec < minSpacingSec) {
                PollOutcome.Skipped(side)
            } else {
                pollSide(side, nowSec)
            }
        }
        store.prune(nowSec - Settings.Defaults.RETENTION_DAYS * 86_400L)
        outcomes
    }

    private suspend fun pollSide(side: Side, nowSec: Long): PollOutcome {
        val amount = settings.amountUsdt
        val ads = try {
            MetricsEngine.bestFirst(fetchWithOneRetry(side), side)
        } catch (e: P2PError) {
            return PollOutcome.Failed(side, e)
        } catch (e: Exception) {
            return PollOutcome.Failed(side, P2PError.Transport(e.message ?: e.javaClass.simpleName))
        }

        val sample = MetricsEngine.makeSample(ads, side, amount, nowSec)
            ?: return PollOutcome.Failed(side, P2PError.EmptyResult)

        // A failed poll writes nothing at all: a gap in the chart is honest, a
        // fabricated price is not.
        try {
            store.append(sample)
            store.replaceSnapshot(side, ads, nowSec)
        } catch (e: Exception) {
            return PollOutcome.Failed(side, P2PError.Malformed(e.message ?: "store write failed"))
        }

        sample.fillablePrice?.let { evaluateAlerts(side, amount, it, nowSec) }
        return PollOutcome.Stored(sample)
    }

    companion object {
        private val gate = Mutex()
    }

    private suspend fun fetchWithOneRetry(side: Side) = try {
        source.fetch(side, settings.fiat, settings.payment, 20)
    } catch (e: Exception) {
        if (retryDelayMs > 0) delay(retryDelayMs)
        source.fetch(side, settings.fiat, settings.payment, 20)
    }

    private fun evaluateAlerts(side: Side, amount: Int, price: Double, nowSec: Long) {
        for (rule in store.alertRules()) {
            if (rule.side != side || rule.amountUsdt != amount) continue
            // An unknown rule starts armed.
            val state = store.alertState(rule.id) ?: AlertState.ARMED
            val decision = AlertEngine.evaluate(rule, price, state)
            if (decision.fire) presenter.present(rule, price)
            store.upsertAlert(rule, decision.newState, if (decision.fire) nowSec else null)
        }
    }
}
