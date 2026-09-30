package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate

/**
 * Adherence, aggregates and reports are all reductions over the log (invariant 4, ADR 0003) —
 * nothing here is a stored, mutated counter. Completed/missed/skipped are always reported as
 * three separate figures, never collapsed into one percentage that hides skips (CLAUDE.md).
 */
object EventLogReduction {
    data class AdherenceFigures(
        val completed: Int,
        val missed: Int,
        val skipped: Int,
    )

    fun adherenceFigures(occurrences: List<Occurrence>): AdherenceFigures {
        var completed = 0
        var missed = 0
        var skipped = 0
        for (occurrence in occurrences) {
            when (occurrence.state) {
                OccurrenceState.COMPLETED -> completed++
                OccurrenceState.MISSED -> missed++
                OccurrenceState.SKIPPED -> skipped++
                OccurrenceState.PENDING, OccurrenceState.SNOOZED -> Unit
            }
        }
        return AdherenceFigures(completed, missed, skipped)
    }

    /**
     * "Days in the last 30 where all critical tasks were completed" (CLAUDE.md — no punitive
     * streaks; a count of qualifying days, not a consecutive-day counter that resets to zero).
     * A day with no critical occurrences at all does not count as qualifying.
     */
    fun criticalCompletionDays(
        occurrencesByDate: Map<LocalDate, List<Occurrence>>,
        criticalityOf: (Occurrence) -> Criticality,
        windowDates: List<LocalDate>,
    ): Int =
        windowDates.count { date ->
            val criticalOccurrences =
                occurrencesByDate[date].orEmpty().filter { criticalityOf(it) == Criticality.CRITICAL }
            criticalOccurrences.isNotEmpty() && criticalOccurrences.all { it.state == OccurrenceState.COMPLETED }
        }
}
