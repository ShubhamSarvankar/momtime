package com.momtime.shared.engine

import com.momtime.shared.domain.Recurrence
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceExpanderTest {
    @Test
    fun `daily expands every date in the window`() {
        val dates = RecurrenceExpander.expand(Recurrence.Daily, LocalDate(2026, 1, 1), LocalDate(2026, 1, 4))
        assertEquals(
            listOf(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2), LocalDate(2026, 1, 3)),
            dates,
        )
    }

    @Test
    fun `weekly expands only matching days of week`() {
        val recurrence = Recurrence.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        // 2026-01-05 is a Monday.
        val dates = RecurrenceExpander.expand(recurrence, LocalDate(2026, 1, 5), LocalDate(2026, 1, 12))
        assertEquals(listOf(LocalDate(2026, 1, 5), LocalDate(2026, 1, 7)), dates)
    }

    // Golden scenario 9: EveryNDays expansion across a month boundary.
    @Test
    fun `everyNDays anchored Jan 30 expands correctly across a February month boundary`() {
        val recurrence = Recurrence.EveryNDays(n = 3, anchorDate = LocalDate(2026, 1, 30))
        val dates = RecurrenceExpander.expand(recurrence, LocalDate(2026, 1, 30), LocalDate(2026, 2, 10))
        // Jan 30, Feb 2, Feb 5, Feb 8 — three days apart throughout, crossing the month boundary
        // with no drift or skipped/duplicated day.
        assertEquals(
            listOf(
                LocalDate(2026, 1, 30),
                LocalDate(2026, 2, 2),
                LocalDate(2026, 2, 5),
                LocalDate(2026, 2, 8),
            ),
            dates,
        )
    }

    // Golden scenario 9: EveryNDays expansion across a leap day.
    @Test
    fun `everyNDays anchored before a leap day expands correctly through it`() {
        // 2028 is a leap year — Feb 29, 2028 exists.
        val recurrence = Recurrence.EveryNDays(n = 2, anchorDate = LocalDate(2028, 2, 27))
        val dates = RecurrenceExpander.expand(recurrence, LocalDate(2028, 2, 27), LocalDate(2028, 3, 5))
        assertEquals(
            listOf(
                LocalDate(2028, 2, 27),
                LocalDate(2028, 2, 29),
                LocalDate(2028, 3, 2),
                LocalDate(2028, 3, 4),
            ),
            dates,
        )
    }

    @Test
    fun `everyNDays before the anchor date produces nothing`() {
        val recurrence = Recurrence.EveryNDays(n = 3, anchorDate = LocalDate(2026, 6, 1))
        val dates = RecurrenceExpander.expand(recurrence, LocalDate(2026, 5, 1), LocalDate(2026, 6, 1))
        assertEquals(emptyList(), dates)
    }
}
