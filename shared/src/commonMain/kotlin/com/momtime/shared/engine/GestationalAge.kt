package com.momtime.shared.engine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * The week of pregnancy on a date, from the due date alone (`ARCHITECTURE.md` section 3.5). A pregnancy is 280 days
 * from the first day of week 0 to the due date, so the week is the whole weeks elapsed. It is a count she can hand to
 * her obstetrician, not an interpretation: nothing is derived from it.
 */
object GestationalAge {
    const val FULL_TERM_DAYS = 280
    const val LAST_WEEK = 42
    private const val DAYS_PER_WEEK = 7

    /**
     * The completed weeks on [onDate] for a pregnancy due on [dueDate], from 0 to 42, or null before week 0 or after
     * week 42. The division floors: a date 283 days before the due date is before week 0, not week 0, which truncating
     * toward zero would say. The caller reads [dueDate] as of the instant it is reporting for
     * (`PregnancyRepository.dueDateRevisionAsOf`), never the latest revision.
     */
    fun weekAt(
        dueDate: LocalDate,
        onDate: LocalDate,
    ): Int? {
        val elapsed = FULL_TERM_DAYS - onDate.daysUntil(dueDate)
        val week = elapsed.floorDiv(DAYS_PER_WEEK)
        return if (week in 0..LAST_WEEK) week else null
    }
}
