package com.momtime.shared.engine

import com.momtime.shared.domain.Recurrence
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Expands a Recurrence into concrete dates. No RRULE parser (ADR 0004) — the engine emits
 * concrete instants rather than handing a rule to the platform.
 */
object RecurrenceExpander {
    /** [from, to) — from inclusive, to exclusive. */
    fun expand(
        recurrence: Recurrence,
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> {
        if (from >= to) return emptyList()
        val dates = mutableListOf<LocalDate>()
        var date = from
        while (date < to) {
            if (matches(recurrence, date)) dates += date
            date = date.plus(DatePeriod(days = 1))
        }
        return dates
    }

    fun matches(
        recurrence: Recurrence,
        date: LocalDate,
    ): Boolean =
        when (recurrence) {
            is Recurrence.Daily -> true
            is Recurrence.Weekly -> dayOfWeekOf(date) in recurrence.daysOfWeek
            is Recurrence.EveryNDays -> {
                if (date < recurrence.anchorDate) {
                    false
                } else {
                    val daysSinceAnchor = date.toEpochDays() - recurrence.anchorDate.toEpochDays()
                    daysSinceAnchor % recurrence.n == 0L
                }
            }
        }

    private fun dayOfWeekOf(date: LocalDate): DayOfWeek = date.dayOfWeek
}
