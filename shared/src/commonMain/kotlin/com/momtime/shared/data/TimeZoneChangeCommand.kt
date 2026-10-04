package com.momtime.shared.data

import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.engine.TravelReschedule
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What a zone change did. */
data class ZoneChangeResult(
    /** How many active templates took the new zone. */
    val templatesMoved: Int,
    /** How many open occurrences were moved to their wall clock time in the new zone. */
    val occurrencesMoved: Int,
)

/**
 * What happens when the device's time zone changes (golden scenario 8, ADR 0068, `ARCHITECTURE.md` section 11).
 * Templates follow the device zone in v1: an 8 AM reminder means 8 AM wherever she is, as quiet hours and the
 * interruption budget already follow her (ADR 0032).
 *
 * - Every **active template's** `timeZoneId` becomes [newZone], so every later materialisation uses it.
 * - Every **open occurrence** (`PENDING` or `SNOOZED`) of such a template whose own zone is not [newZone] moves to
 *   its local date and the template's time of day in the new zone, but never to before [now]
 *   ([TravelReschedule.rescheduledInstant]): travel never makes an occurrence overdue that was not, so a dose cannot
 *   become `MISSED` purely because she flew east. Flying west moves doses later. An occurrence clamped to [now] is
 *   due now, and the fire path decides how it is presented (ADR 0056). It keeps its id, its `alarmSlot` and its
 *   state, so the armed alarm's request code still matches and nothing about its history changes. Rungs that already
 *   fired stay consumed (the count rule); the remaining rungs follow the new instant.
 * - **Terminal occurrences are never touched.** A snooze's end (`snoozedUntil`) is an absolute instant decided when
 *   she snoozed and is not moved either: this command writes no event.
 * - Inactive templates, and the open occurrences of inactive templates, are left exactly as they are.
 *
 * Idempotent: an occurrence already in [newZone] is skipped, so a second broadcast for the same zone, with a later
 * [now], does not move a clamped occurrence again. It is not one transaction across the two repositories, but it
 * is resumable: a run that stopped part way is finished by the next one, with the later clamp for what remained.
 *
 * The caller then materialises the window and calls `ensureArmed`. [now] is the injected clock's, never read here
 * (invariant 8), and the new zone is passed in, read by the platform only through its seam.
 */
class TimeZoneChangeCommand(
    private val templates: ScheduleTemplateRepository,
    private val occurrences: OccurrenceRepository,
) {
    fun dispatch(
        newZone: TimeZone,
        now: Instant,
    ): ZoneChangeResult {
        val active = templates.findAllActive().associateBy { it.id }
        var occurrencesMoved = 0
        // Occurrences first, while each template still holds the zone its occurrences were materialised in: an
        // interrupted run then leaves a template that is still behind, and the next run finds it again.
        for (occurrence in occurrences.findOpen()) {
            val template = active[occurrence.templateId] ?: continue
            if (occurrence.timeZoneId == newZone) continue
            occurrences.reschedule(
                occurrence.id,
                TravelReschedule.rescheduledInstant(occurrence.localDate, template.timeOfDay, newZone, now),
                newZone,
            )
            occurrencesMoved++
        }
        val behind: List<ScheduleTemplate> = active.values.filter { it.timeZoneId != newZone }
        behind.forEach { templates.updateTimeZone(it.id, newZone) }
        return ZoneChangeResult(templatesMoved = behind.size, occurrencesMoved = occurrencesMoved)
    }
}
