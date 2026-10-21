package com.momtime.shared.data

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.engine.TemplateEdit
import com.momtime.shared.engine.TemplateRefusal
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/** What dispatching an edit did. */
sealed interface EditResult {
    /** The edit was written. [withdrawn] are the occurrences it withdrew; [moved] how many took a new instant or criticality. */
    data class Edited(
        val withdrawn: List<Occurrence>,
        val moved: Int,
    ) : EditResult

    /** The template is invalid. Nothing was written. */
    data class Refused(
        val reason: TemplateRefusal,
    ) : EditResult

    /** No template has this id. Nothing was written. */
    data object UnknownTemplate : EditResult
}

/** What creating a template did. */
sealed interface CreateResult {
    data object Created : CreateResult

    /** The template is invalid. Nothing was written. */
    data class Refused(
        val reason: TemplateRefusal,
    ) : CreateResult
}

/**
 * The only way a template changes after it is created, deactivation included (ADR 0079, invariant 3). It takes the
 * edited template and [now] (the injected clock's, never read here, invariant 8) and, in one transaction
 * ([Transactor]), writes the template and then settles each of its open occurrences:
 *
 * - not wanted any more (the template is inactive, or its recurrence leaves the date out): `WITHDRAWN`, with a
 *   `WITHDRAWN` event (source `USER`, no payload), whether or not it has come due;
 * - wanted and come due ([TemplateEdit.cameDue]): untouched, as it finishes under the rules it started with;
 * - wanted and not come due: its instant becomes its local date at the new time of day in the template's zone,
 *   unless that instant is before [now], when it keeps the instant it has, and its criticality becomes the
 *   template's. Its id, date, slot and state stay, and no event is written.
 *
 * Terminal occurrences are never read here: only open ones are, and the schema refuses an update to a terminal row.
 * The id, the pregnancy, the zone and the mission of a template cannot be edited. The caller then runs the
 * materialisation and arming (android's pass, PR 4).
 *
 * [newId] is the same event id source `OccurrenceActionCommand` and `ReconcileCommand` take.
 */
class EditTemplateCommand(
    private val templates: ScheduleTemplateRepository,
    private val occurrences: OccurrenceRepository,
    private val events: EventRepository,
    private val transactor: Transactor,
    private val newId: () -> String,
) {
    fun dispatch(
        edited: ScheduleTemplate,
        now: Instant,
    ): EditResult {
        TemplateEdit.refusal(edited)?.let { return EditResult.Refused(it) }
        return transactor.inTransaction {
            val existing = templates.findById(edited.id) ?: return@inTransaction EditResult.UnknownTemplate
            val template =
                edited.copy(
                    pregnancyId = existing.pregnancyId,
                    timeZoneId = existing.timeZoneId,
                    mission = existing.mission,
                )
            templates.update(template)
            val withdrawn = mutableListOf<Occurrence>()
            var moved = 0
            for (occurrence in occurrences.findForTemplate(template.id).filterNot { it.isTerminal }) {
                if (!TemplateEdit.wants(template, occurrence.localDate)) {
                    occurrences.transition(occurrence.id, OccurrenceState.WITHDRAWN, withdrawal(occurrence, now))
                    withdrawn += occurrence
                    continue
                }
                val fired = events.countByOccurrenceAndType(occurrence.id, EventType.ALARM_FIRED)
                if (TemplateEdit.cameDue(occurrence, fired, now)) continue
                val atNewTime = LocalDateTime(occurrence.localDate, template.timeOfDay).toInstant(template.timeZoneId)
                val instant = if (atNewTime < now) occurrence.scheduledInstant else atNewTime
                if (instant == occurrence.scheduledInstant && template.criticality == occurrence.criticality) continue
                occurrences.applyEdit(occurrence.id, instant, template.criticality)
                moved++
            }
            EditResult.Edited(withdrawn, moved)
        }
    }

    private fun withdrawal(
        occurrence: Occurrence,
        now: Instant,
    ) = Event(
        id = newId(),
        occurrenceId = occurrence.id,
        eventType = EventType.WITHDRAWN,
        deviceTimestamp = now,
        effectiveAt = null,
        source = EventSource.USER,
        payload = EventPayload.None,
    )
}

/** Creates a template: an insert, after the validation an edit applies too (ADR 0079). Writes nothing if refused. */
class CreateTemplateCommand(
    private val templates: ScheduleTemplateRepository,
) {
    fun dispatch(template: ScheduleTemplate): CreateResult {
        TemplateEdit.refusal(template)?.let { return CreateResult.Refused(it) }
        templates.insert(template)
        return CreateResult.Created
    }
}
