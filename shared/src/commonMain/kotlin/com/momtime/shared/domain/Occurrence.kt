package com.momtime.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * A materialised instance of a template on a specific day (ARCHITECTURE.md section 3.2).
 * COMPLETED, SKIPPED and MISSED are terminal and immutable — never touched by template edits.
 * alarmSlot is per-occurrence, shared across every rung of this occurrence's ladder, not
 * per-rung (ADR 0031 narrowing note).
 */
data class Occurrence(
    val id: String,
    val templateId: String,
    val localDate: LocalDate,
    val scheduledInstant: Instant,
    val timeZoneId: TimeZone,
    val state: OccurrenceState,
    val alarmSlot: Int,
) {
    val isTerminal: Boolean
        get() =
            state == OccurrenceState.COMPLETED ||
                state == OccurrenceState.SKIPPED ||
                state == OccurrenceState.MISSED
}
