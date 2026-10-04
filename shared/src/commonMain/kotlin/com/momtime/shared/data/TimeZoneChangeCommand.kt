package com.momtime.shared.data

import com.momtime.shared.engine.TravelReschedule
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What a zone change did. */
data class ZoneChangeResult(
    /** How many templates took the new zone. */
    val templatesMoved: Int,
    /** How many open occurrences were moved to their wall clock time in the new zone. */
    val occurrencesMoved: Int,
)

/**
 * What happens when the device's time zone changes (golden scenario 8, ADR 0068, `ARCHITECTURE.md` section 11).
 * Templates follow the device zone in v1: an 8 AM reminder means 8 AM wherever she is, as quiet hours and the
 * interruption budget already follow her (ADR 0032).
 *
 * - Every **template's** `timeZoneId` becomes [newZone], active or not, so every later materialisation uses it and a
 *   template reactivated later is already in her zone.
 * - Every **open occurrence** (`PENDING` or `SNOOZED`) whose own zone is not [newZone] moves to its local date and
 *   its template's time of day in the new zone, but never to before [now]
 *   ([TravelReschedule.rescheduledInstant]): travel never makes an occurrence overdue that was not, so a dose cannot
 *   become `MISSED` purely because she flew east. Flying west moves doses later. An occurrence clamped to [now] is
 *   due now, and the fire path decides how it is presented (ADR 0056). An inactive template's open occurrences can
 *   still ring, so they move too. It keeps its id, its `alarmSlot` and its state, so the armed alarm's request code
 *   still matches and nothing about its history changes. Rungs that already fired stay consumed (the count rule); the
 *   remaining rungs follow the new instant.
 * - **Terminal occurrences are never touched**, here and in the schema: any update to a terminal row aborts. A
 *   snooze's end (`snoozedUntil`) is an absolute instant decided when she snoozed and is not moved either: this
 *   command writes no event.
 *
 * **One transaction.** The whole command runs in one database transaction ([Transactor]). A materialisation run
 * that landed between the moves and the templates' update would read the old zone and insert new dates at the wrong
 * instants, which the idempotence guard would then keep from being repaired; one of her actions completing an
 * occurrence between this command's read and its update is the same race. ADR 0036's serialisation makes a
 * contending run wait, so it runs entirely before the command (and the command then moves what it inserted) or
 * entirely after (and uses the new zone).
 *
 * Idempotent: an occurrence already in [newZone] is skipped, so a second broadcast for the same zone, with a later
 * [now], does not move a clamped occurrence again.
 *
 * The caller then materialises the window and calls `ensureArmed`. [now] is the injected clock's, never read here
 * (invariant 8), and the new zone is passed in, read by the platform only through its seam.
 */
class TimeZoneChangeCommand(
    private val templates: ScheduleTemplateRepository,
    private val occurrences: OccurrenceRepository,
    private val transactor: Transactor,
) {
    fun dispatch(
        newZone: TimeZone,
        now: Instant,
    ): ZoneChangeResult =
        transactor.inTransaction {
            val all = templates.findAll().associateBy { it.id }
            var occurrencesMoved = 0
            for (occurrence in occurrences.findOpen()) {
                // A foreign key ties every occurrence to a template, so it is there; no occurrence is skipped for lacking one.
                val template = all.getValue(occurrence.templateId)
                if (occurrence.timeZoneId == newZone) continue
                occurrences.reschedule(
                    occurrence.id,
                    TravelReschedule.rescheduledInstant(occurrence.localDate, template.timeOfDay, newZone, now),
                    newZone,
                )
                occurrencesMoved++
            }
            val behind = all.values.filter { it.timeZoneId != newZone }
            behind.forEach { templates.updateTimeZone(it.id, newZone) }
            ZoneChangeResult(templatesMoved = behind.size, occurrencesMoved = occurrencesMoved)
        }
}
