package com.momtime.android.arming

import com.momtime.android.ring.RingSessions
import com.momtime.shared.data.DeliveryPolicyCommand
import com.momtime.shared.domain.Criticality
import com.momtime.shared.engine.RungDelivery
import kotlin.time.Instant

/**
 * What the fire path hands to delivery besides the rung: the domain's decision, and whether a ring is already
 * going.
 */
internal data class DeliveryDecision(
    val policy: RungDelivery,
    val continuing: Boolean,
)

/**
 * Asks the domain what a rung that is about to be delivered should do, and spends the interruption budget for it
 * (ADR 0060, ADR 0062). Android reimplements neither quiet hours nor the budget: [DeliveryPolicyCommand] applies
 * Phase 1's resolution to her settings and today's count.
 *
 * - An occurrence already in the ring session is **continuing**: its ring goes on, no decision is asked and
 *   nothing is spent, because it is one interruption and not two. A Gentle occurrence never joins a session
 *   (ADR 0089), so it is never continuing.
 * - Otherwise, if the rung may ring ([mayRing]: it is within the catch up window), the domain decides, given the
 *   occurrence's own criticality (ADR 0079 item 6). A rung that is to ring spends the budget once; a plain
 *   notification (a Gentle occurrence) and a silent one spend nothing.
 * - A rung beyond the window is a silent notice whatever the policy says, so the domain is not asked and nothing
 *   is spent: it does not interrupt her.
 */
internal class DeliveryDecider(
    private val policy: DeliveryPolicyCommand,
    private val sessions: RingSessions,
) {
    fun decide(
        occurrenceId: String,
        criticality: Criticality,
        now: Instant,
        mayRing: Boolean,
    ): DeliveryDecision =
        when {
            sessions.isRinging(occurrenceId) -> DeliveryDecision(RungDelivery.RING, continuing = true)
            !mayRing -> DeliveryDecision(RungDelivery.SILENT_NOTIFICATION, continuing = false)
            else -> DeliveryDecision(decideAndSpend(criticality, now), continuing = false)
        }

    private fun decideAndSpend(
        criticality: Criticality,
        now: Instant,
    ): RungDelivery {
        val delivery = policy.decide(criticality, now)
        if (delivery == RungDelivery.RING) policy.recordRing(now)
        return delivery
    }
}
