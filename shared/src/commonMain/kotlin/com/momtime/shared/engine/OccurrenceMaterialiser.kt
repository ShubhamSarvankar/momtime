package com.momtime.shared.engine

import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.ScheduleTemplate
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Materialises occurrences over a rolling window, idempotent (ARCHITECTURE.md section 3.2,
 * golden scenario 12). Pure function — id and alarmSlot allocation are injected so the caller
 * (a repository, transactionally) owns those side effects; the engine itself makes no I/O call
 * and reads no clock directly (invariant 8 — `now` and the window bounds are parameters).
 */
object OccurrenceMaterialiser {
    /**
     * @param alreadyMaterialisedDates dates for which this template already has an occurrence
     *   row that is not withdrawn — the UNIQUE(template_id, local_date) index is the schema-level
     *   backstop, this is the engine-level idempotency check that avoids even trying to insert a
     *   duplicate. Each occurrence takes the template's criticality as it is now (ADR 0079).
     */
    fun materialise(
        template: ScheduleTemplate,
        windowStart: Instant,
        windowEnd: Instant,
        alreadyMaterialisedDates: Set<LocalDate>,
        generateId: () -> String,
        allocateSlot: () -> Int,
    ): List<Occurrence> {
        if (!template.active) return emptyList()
        val zone: TimeZone = template.timeZoneId

        val fromDate = zonedDate(windowStart, zone)
        // Inclusive of the day windowEnd falls on, so a late-in-day scheduledInstant near the
        // window boundary isn't excluded by date truncation.
        val toDateExclusive = zonedDate(windowEnd, zone).plus(DatePeriod(days = 1))

        val candidateDates = RecurrenceExpander.expand(template.recurrence, fromDate, toDateExclusive)

        return candidateDates
            .filterNot { it in alreadyMaterialisedDates }
            .mapNotNull { date ->
                val scheduledInstant = LocalDateTime(date, template.timeOfDay).toInstant(zone)
                if (scheduledInstant < windowStart || scheduledInstant >= windowEnd) {
                    null
                } else {
                    Occurrence(
                        id = generateId(),
                        templateId = template.id,
                        localDate = date,
                        scheduledInstant = scheduledInstant,
                        timeZoneId = zone,
                        state = OccurrenceState.PENDING,
                        alarmSlot = allocateSlot(),
                        criticality = template.criticality,
                    )
                }
            }
    }

    private fun zonedDate(
        instant: Instant,
        zone: TimeZone,
    ): LocalDate = instant.toLocalDateTime(zone).date
}
