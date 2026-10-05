package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate

/**
 * The pre-ADR-0040 implementation, kept verbatim as a reference. It reads the occurrence state
 * column. The production reduction reads the event log instead; for histories without
 * COMPLETED_BACKFILLED and with asOf at or after every effect time, the two must agree exactly
 * (EventLogReductionTest oracle property). Test sources only: production code never reads state.
 */
internal object StateBasedAdherenceOracle {
    fun adherenceFigures(occurrences: List<Occurrence>): EventLogReduction.AdherenceFigures {
        var completed = 0
        var missed = 0
        var skipped = 0
        for (occurrence in occurrences) {
            when (occurrence.state) {
                OccurrenceState.COMPLETED -> completed++
                OccurrenceState.MISSED -> missed++
                OccurrenceState.SKIPPED -> skipped++
                OccurrenceState.PENDING, OccurrenceState.SNOOZED -> Unit
                // A withdrawn occurrence is terminal and is not an outcome: it counts nothing (ADR 0079).
                OccurrenceState.WITHDRAWN -> Unit
            }
        }
        return EventLogReduction.AdherenceFigures(completed, missed, skipped)
    }

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
