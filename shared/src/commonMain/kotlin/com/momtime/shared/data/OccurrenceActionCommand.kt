package com.momtime.shared.data

import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.OccurrenceAction
import com.momtime.shared.engine.OccurrenceActions
import com.momtime.shared.engine.SnoozePolicy
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What dispatching one of her actions did. */
sealed interface ActionResult {
    /** The action was taken: its one event was written and the occurrence changed state with it. */
    data object Done : ActionResult

    /** The action is not available (a fourth snooze, one past the next occurrence, a terminal occurrence). */
    data object NotAvailable : ActionResult

    /** No occurrence has this id. Nothing was written. */
    data object UnknownOccurrence : ActionResult
}

/**
 * Her three actions on an occurrence, as a command to the domain (invariant 3). The ring screen and the
 * notification buttons dispatch to this and the domain decides: no Activity, receiver or service changes an
 * occurrence's state.
 *
 * Each action writes exactly its own event and the state change that goes with it, in one transaction
 * ([OccurrenceRepository.transition]), and nothing else: acknowledge is `COMPLETED` and the state `COMPLETED`;
 * skip is `SKIPPED` and the state `SKIPPED`; snooze is `SNOOZED` carrying its number and the state `SNOOZED`.
 * Whether an action is available is [OccurrenceActions.available], which asks `SnoozePolicy` for the cap and
 * for the next occurrence of the same template. An action that is not available is refused with
 * [ActionResult.NotAvailable] and nothing is written, never silently ignored.
 *
 * The snooze duration is her setting (`AppSettings.snoozeDurationMinutes`, 10 by default), read once, when she
 * snoozes: the end it gives is recorded in the `SNOOZED` event and is what everything else reads. Dispatching
 * writes no `ALARM_*` event and arms nothing: the caller arms through `ensureArmed`, which reads the occurrence's
 * running snooze ([runningSnoozeEnd]).
 */
class OccurrenceActionCommand(
    private val occurrences: OccurrenceRepository,
    private val events: EventRepository,
    private val settings: AppSettingsRepository,
    private val newId: () -> String,
) {
    /** The actions she may take on [occurrenceId] at [now]. Empty if it is terminal or does not exist. */
    fun available(
        occurrenceId: String,
        now: Instant,
    ): Set<OccurrenceAction> = occurrences.findById(occurrenceId)?.let { available(it, now) }.orEmpty()

    fun dispatch(
        occurrenceId: String,
        action: OccurrenceAction,
        now: Instant,
    ): ActionResult {
        val occurrence = occurrences.findById(occurrenceId) ?: return ActionResult.UnknownOccurrence
        if (action !in available(occurrence, now)) return ActionResult.NotAvailable
        when (action) {
            OccurrenceAction.ACKNOWLEDGE ->
                occurrences.transition(
                    occurrenceId,
                    OccurrenceState.COMPLETED,
                    OccurrenceActions.acknowledged(newId(), occurrenceId, now),
                )
            OccurrenceAction.SKIP ->
                occurrences.transition(
                    occurrenceId,
                    OccurrenceState.SKIPPED,
                    OccurrenceActions.skipped(newId(), occurrenceId, now),
                )
            OccurrenceAction.SNOOZE -> snooze(occurrence, now)
        }
        return ActionResult.Done
    }

    /**
     * Takes a snooze: the end is decided here, once, from the duration in force now and the next occurrence of the
     * template, and recorded in the event ([EventPayload.Snooze.snoozedUntil]). The caller checked that the snooze
     * is available, so [SnoozePolicy.snoozedUntil] has an end to give.
     */
    private fun snooze(
        occurrence: Occurrence,
        now: Instant,
    ) {
        val count = snoozeCount(occurrence.id)
        val until =
            checkNotNull(
                SnoozePolicy.snoozedUntil(
                    now,
                    snoozeDuration(),
                    count,
                    nextOccurrenceOfSameTemplate(occurrence),
                ),
            ) { "a snooze that is available has an end" }
        occurrences.transition(
            occurrence.id,
            OccurrenceState.SNOOZED,
            OccurrenceActions.snoozed(newId(), occurrence.id, now, count + 1, until),
        )
    }

    /**
     * When the snooze of [occurrence] that is still running ends, or null if none is. It is the end recorded in
     * the latest `SNOOZED` event, while fewer `SNOOZE_ENDED` than `SNOOZED` events exist. It never reads her
     * snooze setting: the end was decided when she snoozed (ADR 0066).
     */
    fun runningSnoozeEnd(occurrence: Occurrence): Instant? {
        if (occurrence.state != OccurrenceState.SNOOZED) return null
        val snoozes =
            events
                .findByOccurrenceAndType(occurrence.id, EventType.SNOOZED)
                .mapNotNull { it.payload as? EventPayload.Snooze }
        // A divergent row: the state says SNOOZED and the log holds no snooze, or none that records its end. There
        // is no snooze to end.
        val latest = snoozes.maxByOrNull { it.snoozeNumber } ?: return null
        return OccurrenceActions.runningSnoozeEnd(
            state = occurrence.state,
            lastSnoozedUntil = latest.snoozedUntil,
            snoozedCount = snoozes.size,
            endedCount = events.countByOccurrenceAndType(occurrence.id, EventType.SNOOZE_ENDED).toInt(),
        )
    }

    private fun available(
        occurrence: Occurrence,
        now: Instant,
    ): Set<OccurrenceAction> =
        OccurrenceActions.available(
            state = occurrence.state,
            snoozeCount = snoozeCount(occurrence.id),
            now = now,
            snoozeDuration = snoozeDuration(),
            nextOccurrenceOfSameTemplate = nextOccurrenceOfSameTemplate(occurrence),
        )

    private fun snoozeCount(occurrenceId: String): Int =
        events.countByOccurrenceAndType(occurrenceId, EventType.SNOOZED).toInt()

    private fun snoozeDuration() = settings.current().snoozeDurationMinutes.minutes

    /**
     * The first materialised occurrence of the same template after this one. Only the rolling window is
     * materialised (ARCHITECTURE.md section 3.2), so a template whose next occurrence is beyond it has none
     * here, and a snooze of minutes cannot reach it.
     */
    private fun nextOccurrenceOfSameTemplate(occurrence: Occurrence): Instant? =
        occurrences
            .findForTemplate(occurrence.templateId)
            .map { it.scheduledInstant }
            .filter { it > occurrence.scheduledInstant }
            .minOrNull()
}
