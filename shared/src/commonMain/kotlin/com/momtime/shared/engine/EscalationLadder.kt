package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EscalationRung
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Generates the full escalation ladder for an occurrence as a declarative, ordered list (ADR
 * 0010) — escalation increases channel reach, not intensity of the same channel (ADR 0011): a
 * CRITICAL occurrence rings full-scale at t+0, it does not build up to it. All three ladders
 * emit RING at t+0; how intensely a platform renders a RING rung (full-screen alarm vs. plain
 * notification) is informed by criticality but is a platform/EscalationPolicy decision, not a
 * different channel.
 */
object EscalationLadder {
    fun forOccurrence(
        scheduledInstant: Instant,
        criticality: Criticality,
    ): List<EscalationRung> =
        when (criticality) {
            Criticality.CRITICAL ->
                listOf(
                    EscalationRung(scheduledInstant, Channel.RING),
                    EscalationRung(scheduledInstant + 5.minutes, Channel.RING_REPEAT),
                    EscalationRung(scheduledInstant + 10.minutes, Channel.CAREGIVER_INFO),
                    EscalationRung(scheduledInstant + 20.minutes, Channel.CAREGIVER_URGENT),
                )
            Criticality.STANDARD ->
                listOf(
                    EscalationRung(scheduledInstant, Channel.RING),
                    EscalationRung(scheduledInstant + 10.minutes, Channel.RING_REPEAT),
                )
            Criticality.GENTLE ->
                listOf(
                    EscalationRung(scheduledInstant, Channel.RING),
                )
        }
}
