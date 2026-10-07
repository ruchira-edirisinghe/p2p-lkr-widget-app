package dev.dfanso.lkrp2p.core

import java.util.UUID

enum class ThresholdDirection { ABOVE, BELOW }

enum class AlertState {
    /** Waiting for a crossing. */
    ARMED,

    /** Already notified; will not notify again until the price crosses back. */
    TRIGGERED,
}

data class AlertRule(
    val id: String = UUID.randomUUID().toString(),
    val side: Side,
    val amountUsdt: Int,
    val threshold: Double,
    val direction: ThresholdDirection,
)

data class AlertDecision(val fire: Boolean, val newState: AlertState)

object AlertEngine {
    /**
     * Edge-triggered, not level-triggered: a notification fires on the
     * transition into the threshold and the rule then re-arms only once the
     * price crosses back. Without this the collector would notify on every poll
     * for as long as the rate stayed past the threshold.
     */
    fun evaluate(rule: AlertRule, price: Double, state: AlertState): AlertDecision {
        val beyond = when (rule.direction) {
            ThresholdDirection.ABOVE -> price >= rule.threshold
            ThresholdDirection.BELOW -> price <= rule.threshold
        }
        return when {
            beyond && state == AlertState.ARMED -> AlertDecision(true, AlertState.TRIGGERED)
            beyond -> AlertDecision(false, AlertState.TRIGGERED)
            else -> AlertDecision(false, AlertState.ARMED)
        }
    }
}
