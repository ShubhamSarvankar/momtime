package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Occurrence

/**
 * One occurrence as selection sees it: the occurrence, its criticality (which decides its ladder), and how
 * many of its rungs have fired. [firedCount] is the count of `ALARM_FIRED` events in the log, never a
 * comparison of a rung's instant with the current time (golden scenario 14): a device clock set backward
 * must not bring a fired rung back.
 */
data class ArmCandidate(
    val occurrence: Occurrence,
    val criticality: Criticality,
    val firedCount: Int,
)

/** The rung to arm next, and the occurrence and slot it belongs to. */
data class RungSelection(
    val occurrenceId: String,
    val alarmSlot: Int,
    val rung: EscalationRung,
)

/**
 * Which rung is armed next, across every candidate (ADR 0017, ARCHITECTURE.md section 5.3). Pure: it reads
 * no clock and schedules nothing, and the caller says which channels it delivers (invariant 6).
 */
object ArmingSelection {
    /** The next rung of [channels] across all [candidates], or null if none is pending. */
    fun next(
        candidates: List<ArmCandidate>,
        channels: Set<Channel>,
    ): RungSelection? {
        val pending = candidates.map { NextRungResolver.PendingLadder(it.occurrence.id, remainingFor(it, channels)) }
        val (occurrenceId, rung) = NextRungResolver.globalNext(pending, channels) ?: return null
        val slot = candidates.first { it.occurrence.id == occurrenceId }.occurrence.alarmSlot
        return RungSelection(occurrenceId, slot, rung)
    }

    /**
     * The rung that should fire next for one occurrence: the one that is expected when an alarm for it
     * arrives. Null when every rung of [channels] has fired.
     */
    fun expectedFor(
        candidate: ArmCandidate,
        channels: Set<Channel>,
    ): EscalationRung? = remainingFor(candidate, channels).firstOrNull()

    private fun remainingFor(
        candidate: ArmCandidate,
        channels: Set<Channel>,
    ): List<EscalationRung> =
        NextRungResolver.remaining(
            EscalationLadder.forOccurrence(candidate.occurrence.scheduledInstant, candidate.criticality),
            candidate.firedCount,
            channels,
        )
}
