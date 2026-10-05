package com.momtime.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * A materialised instance of a template on a specific day (ARCHITECTURE.md section 3.2).
 * COMPLETED, SKIPPED, MISSED and WITHDRAWN are terminal and immutable — never touched by template edits.
 * WITHDRAWN is an occurrence its template no longer wants (ADR 0079): terminal, and not an outcome.
 * criticality is the template's when the occurrence was materialised, a snapshot as timeZoneId is (ADR 0079).
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
    val criticality: Criticality,
) {
    val isTerminal: Boolean
        get() = state.isTerminal
}
