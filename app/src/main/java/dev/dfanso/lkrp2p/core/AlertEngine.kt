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

enum class MoveDirection(val label: String) { UP("Rises"), DOWN("Falls"), EITHER("Moves") }

/** "Tell me when the rate moves [percent]% within [hours] hours." */
data class MoveAlertRule(
    val id: String = UUID.randomUUID().toString(),
    val amountUsdt: Int,
    val percent: Double,
    val hours: Int,
    val direction: MoveDirection,
)

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

    /** Percent change from [reference] to [price]. */
    fun change(reference: Double, price: Double): Double = (price - reference) / reference * 100

    /** Edge-triggered like [evaluate]: fires once, re-arms when the move shrinks back. */
    fun evaluateMove(rule: MoveAlertRule, changePercent: Double, state: AlertState): AlertDecision {
        val beyond = when (rule.direction) {
            MoveDirection.UP -> changePercent >= rule.percent
            MoveDirection.DOWN -> changePercent <= -rule.percent
            MoveDirection.EITHER -> kotlin.math.abs(changePercent) >= rule.percent
        }
        return when {
            beyond && state == AlertState.ARMED -> AlertDecision(true, AlertState.TRIGGERED)
            beyond -> AlertDecision(false, AlertState.TRIGGERED)
            else -> AlertDecision(false, AlertState.ARMED)
        }
    }
}
