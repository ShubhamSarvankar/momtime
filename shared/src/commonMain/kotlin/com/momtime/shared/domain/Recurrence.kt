package com.momtime.shared.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/**
 * A narrow custom recurrence model, not RFC 5545. No RRULE parser anywhere in the codebase
 * (ADR 0004) — the engine expands any of these into concrete instants.
 */
sealed interface Recurrence {
    data object Daily : Recurrence

    data class Weekly(
        val daysOfWeek: Set<DayOfWeek>,
    ) : Recurrence

    data class EveryNDays(
        val n: Int,
        val anchorDate: LocalDate,
    ) : Recurrence
}
