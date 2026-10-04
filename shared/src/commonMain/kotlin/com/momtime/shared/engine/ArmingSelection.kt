package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Occurrence
import kotlin.time.Instant

/**
 * One occurrence as selection sees it: the occurrence, its criticality (which decides its ladder), and when
 * its rungs fired. [firedAt] is the `deviceTimestamp` of each of its `ALARM_FIRED` events, oldest first: the
 * record of rungs that fired, read from the log and never inferred from a comparison of a rung's instant with
 * the current time (golden scenario 14). A device clock set backward must not bring a fired rung back.
 */
data class ArmCandidate(
    val occurrence: Occurrence,
    val criticality: Criticality,
    val firedAt: List<Instant>,
)

/** The rung to arm next, and the occurrence and slot it belongs to. */
data class RungSelection(
    val occurrenceId: String,
    val alarmSlot: Int,
    val rung: EscalationRung,
)

/**
 * Which rung is armed next, across every candidate (ADR 0017, ARCHITECTURE.md section 5.3). Pure: it reads
 * no clock (the caller passes `now`) and schedules nothing, and the caller says which channels it delivers
 * (invariant 6). A rung found overdue beyond the catch up window is skipped, not rung (ADR 0056).
 */
object ArmingSelection {
    /** The next rung of [channels] across all [candidates] as of [now], or null if none is pending. */
    fun next(
        candidates: List<ArmCandidate>,
        channels: Set<Channel>,
        now: Instant,
    ): RungSelection? {
        val pending =
            candidates.map { NextRungResolver.PendingLadder(it.occurrence.id, remainingFor(it, channels, now)) }
        val (occurrenceId, rung) = NextRungResolver.globalNext(pending, channels) ?: return null
        val slot = candidates.first { it.occurrence.id == occurrenceId }.occurrence.alarmSlot
        return RungSelection(occurrenceId, slot, rung)
    }

    /**
     * The rung that should fire next for one occurrence as of [now]: the one that is expected when an alarm for
     * it arrives. Null when every rung of [channels] has fired or is too late to ring.
     */
    fun expectedFor(
        candidate: ArmCandidate,
        channels: Set<Channel>,
        now: Instant,
    ): EscalationRung? = remainingFor(candidate, channels, now).firstOrNull()

    private fun remainingFor(
        candidate: ArmCandidate,
        channels: Set<Channel>,
        now: Instant,
    ): List<EscalationRung> =
        CatchUp.remaining(
            EscalationLadder.forOccurrence(candidate.occurrence.scheduledInstant, candidate.criticality).filter {
                it.channel in channels
            },
            candidate.firedAt,
            now,
        )
}
