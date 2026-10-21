package com.momtime.shared.engine

import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Why a template was refused. The command writes nothing when it returns one of these. */
enum class TemplateRefusal {
    /** The title is empty or longer than [TemplateEdit.MAX_TITLE_LENGTH]. */
    TITLE_LENGTH,

    /** An `EveryNDays` interval below 1. */
    INTERVAL_BELOW_ONE,

    /** A `Weekly` recurrence with no weekday. */
    NO_WEEKDAYS,
}

/**
 * The pure decisions of a template edit (ADR 0079). Pure: no clock, no repository, and nothing here reads a template's
 * criticality, which only the command copies onto an occurrence (invariant checked by `verifyCriticalityFromOccurrence`).
 */
object TemplateEdit {
    const val MAX_TITLE_LENGTH = 24

    /**
     * An occurrence has come due when any one of these holds at the edit's [now]: its `scheduledInstant` is at or
     * before [now]; or it has at least one `ALARM_FIRED` event ([firedCount]); or its state is `SNOOZED` (ADR 0079).
     */
    fun cameDue(
        occurrence: Occurrence,
        firedCount: Long,
        now: Instant,
    ): Boolean = occurrence.scheduledInstant <= now || firedCount > 0 || occurrence.state == OccurrenceState.SNOOZED

    /** Whether [template] still wants an occurrence on [localDate]: it is active and its recurrence includes the date. */
    fun wants(
        template: ScheduleTemplate,
        localDate: LocalDate,
    ): Boolean = template.active && RecurrenceExpander.matches(template.recurrence, localDate)

    /** The first reason [template] is refused, or null if it is valid. */
    fun refusal(template: ScheduleTemplate): TemplateRefusal? =
        when {
            template.title.length !in 1..MAX_TITLE_LENGTH -> TemplateRefusal.TITLE_LENGTH
            template.recurrence is Recurrence.EveryNDays && template.recurrence.n < 1 ->
                TemplateRefusal.INTERVAL_BELOW_ONE
            template.recurrence is Recurrence.Weekly && template.recurrence.daysOfWeek.isEmpty() ->
                TemplateRefusal.NO_WEEKDAYS
            else -> null
        }
}
