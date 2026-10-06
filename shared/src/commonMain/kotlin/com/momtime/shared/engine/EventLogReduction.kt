package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Adherence, aggregates and reports are all reductions over the log (invariant 4, ADR 0003) —
 * nothing here is a stored, mutated counter, and nothing reads occurrence state. Completed,
 * missed and skipped are always reported as three separate figures, never collapsed into one
 * percentage that hides skips (CLAUDE.md).
 *
 * Attribution and time semantics are ADR 0040:
 * - Every event is attributed to its occurrence's scheduled localDate.
 * - An event's effect time is effectiveAt for derived events (MISSED) and deviceTimestamp for user
 *   events. It counts in a figure only if effect time <= asOf (inclusive). asOf is a parameter;
 *   the reduction is pure and never reads a clock.
 * - An occurrence with no counted terminal event as of asOf is in none of the three figures, the
 *   same as a PENDING or SNOOZED occurrence always was.
 * - COMPLETED_BACKFILLED counts as completed, on the scheduled date, never as missed.
 */
object EventLogReduction {
    data class AdherenceFigures(
        val completed: Int,
        val missed: Int,
        val skipped: Int,
    )

    private enum class Outcome { COMPLETED, SKIPPED, MISSED }

    private fun Event.effectTime(): Instant = effectiveAt ?: deviceTimestamp

    private fun Event.outcome(): Outcome? =
        when (eventType) {
            EventType.COMPLETED, EventType.COMPLETED_BACKFILLED -> Outcome.COMPLETED
            EventType.SKIPPED -> Outcome.SKIPPED
            EventType.MISSED -> Outcome.MISSED
            else -> null
        }

    private class Counted(
        val outcome: Outcome,
        val at: Instant,
    )

    /**
     * The outcome of each occurrence as of [asOf]: the counted terminal event with the latest
     * effect time wins, so a backfill entered after a miss turns missed into completed from its
     * own effect time onward. Ties prefer completion, then skip, then miss.
     */
    private fun outcomes(
        events: List<Event>,
        asOf: Instant,
    ): Map<String, Outcome> {
        val best = HashMap<String, Counted>()
        for (event in events) {
            val occurrenceId = event.occurrenceId ?: continue
            val outcome = event.outcome() ?: continue
            val at = event.effectTime()
            if (at > asOf) continue
            val current = best[occurrenceId]
            if (current == null || supersedes(at, outcome, current)) best[occurrenceId] = Counted(outcome, at)
        }
        return best.mapValues { it.value.outcome }
    }

    private fun supersedes(
        at: Instant,
        outcome: Outcome,
        current: Counted,
    ): Boolean = if (at != current.at) at > current.at else outcome < current.outcome

    fun adherenceFigures(
        occurrences: List<Occurrence>,
        events: List<Event>,
        asOf: Instant,
    ): AdherenceFigures {
        val outcomes = outcomes(events, asOf)
        var completed = 0
        var missed = 0
        var skipped = 0
        for (occurrence in occurrences) {
            when (outcomes[occurrence.id]) {
                Outcome.COMPLETED -> completed++
                Outcome.MISSED -> missed++
                Outcome.SKIPPED -> skipped++
                null -> Unit
            }
        }
        return AdherenceFigures(completed, missed, skipped)
    }

    /**
     * "Days in the last 30 where all critical tasks were completed" (CLAUDE.md — no punitive
     * streaks; a count of qualifying days, not a consecutive-day counter that resets to zero).
     * A day with no critical occurrences at all does not count as qualifying. A critical
     * occurrence with no counted terminal event as of [asOf] is not completed. Whether an occurrence is
     * critical is its own `criticality` (ADR 0079 item 6): a day already lived keeps the criticality it had.
     */
    fun criticalCompletionDays(
        occurrencesByDate: Map<LocalDate, List<Occurrence>>,
        events: List<Event>,
        windowDates: List<LocalDate>,
        asOf: Instant,
    ): Int {
        val outcomes = outcomes(events, asOf)
        return windowDates.count { date ->
            val critical =
                occurrencesByDate[date].orEmpty().filter { occurrence ->
                    occurrence.criticality == Criticality.CRITICAL
                }
            critical.isNotEmpty() && critical.all { outcomes[it.id] == Outcome.COMPLETED }
        }
    }
}
