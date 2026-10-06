package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Occurrence
import kotlin.time.Instant

/**
 * One occurrence as selection sees it: the occurrence, whose own `criticality` decides its ladder (ADR 0079
 * item 6: the template's is never read here, so a template edit cannot change a ladder in mid flight), and how
 * many of its rungs have fired. [snoozeEnd] is when the snooze that is running ends, or null if none is
 * ([OccurrenceActions.runningSnoozeEnd]). [firedCount] is the count of `ALARM_FIRED` events in the log, never a
 * comparison of a rung's instant with the current time (golden scenario 14): a device clock set backward
 * must not bring a fired rung back.
 */
data class ArmCandidate(
    val occurrence: Occurrence,
    val firedCount: Int,
    val snoozeEnd: Instant? = null,
)

/**
 * The rung to arm next, and the occurrence and slot it belongs to. [snoozeWake] is true when it is not a ladder
 * rung but the end of a running snooze, carried as a rung of the `RING` channel at the snooze's end: the one thing
 * that is armed for an occurrence while it is snoozed.
 */
data class RungSelection(
    val occurrenceId: String,
    val alarmSlot: Int,
    val rung: EscalationRung,
    val snoozeWake: Boolean = false,
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
        val candidate = candidates.first { it.occurrence.id == occurrenceId }
        return RungSelection(occurrenceId, candidate.occurrence.alarmSlot, rung, candidate.snoozeEnd != null)
    }

    /**
     * The rung that should fire next for one occurrence: the one that is expected when an alarm for it
     * arrives. Null when every rung of [channels] has fired.
     */
    fun expectedFor(
        candidate: ArmCandidate,
        channels: Set<Channel>,
    ): EscalationRung? = remainingFor(candidate, channels).firstOrNull()

    /**
     * While a snooze is running the only thing armed for the occurrence is its end: a rung that comes due during
     * the snooze waits, and is armed once the snooze has ended, from the same count of fired rungs as before (so a
     * snooze consumes none). The snooze's end is a `RING` rung, so it is delivered only by a caller that delivers
     * `RING`.
     */
    private fun remainingFor(
        candidate: ArmCandidate,
        channels: Set<Channel>,
    ): List<EscalationRung> {
        val snoozeEnd = candidate.snoozeEnd
        if (snoozeEnd != null) return listOf(EscalationRung(snoozeEnd, Channel.RING)).filter { it.channel in channels }
        return NextRungResolver.remaining(
            EscalationLadder.forOccurrence(candidate.occurrence.scheduledInstant, candidate.occurrence.criticality),
            candidate.firedCount,
            channels,
        )
    }
}
