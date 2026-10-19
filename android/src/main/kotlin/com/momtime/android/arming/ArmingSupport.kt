package com.momtime.android.arming

import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.OccurrenceActionCommand
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.engine.AlarmEvents
import com.momtime.shared.engine.ArmCandidate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What selection reads: the occurrences that are open (pending, or snoozed), each carrying the criticality that
 * decides its ladder (its own, never its template's: ADR 0079 item 6), the count of its `ALARM_FIRED` events, which
 * is the record of the rungs that have fired, and the end of the snooze that is running, if one is (ADR 0066). A
 * snoozed occurrence is selected by its snooze's end, which is how the one `ensureArmed` entry point arms a snooze
 * and the watchdog repairs a lost one. The template is not read here.
 */
internal class ArmCandidates(
    private val occurrences: OccurrenceRepository,
    private val events: EventRepository,
    private val actions: OccurrenceActionCommand,
) {
    fun pending(): List<ArmCandidate> = occurrences.findOpen().map(::of)

    /** The occurrence that owns [slot], or null if none does. */
    fun owning(slot: Int): Occurrence? = occurrences.findByAlarmSlot(slot)

    fun of(occurrence: Occurrence): ArmCandidate =
        ArmCandidate(
            occurrence = occurrence,
            firedCount = events.countByOccurrenceAndType(occurrence.id, EventType.ALARM_FIRED).toInt(),
            snoozeEnd = actions.runningSnoozeEnd(occurrence),
        )
}

/** The alarm path's writes to the event log, and its notion of now. The shapes are `AlarmEvents`'. */
internal class AlarmLog(
    private val events: EventRepository,
    private val clock: Clock,
    private val newId: () -> String,
) {
    fun now(): Instant = clock.now()

    /** Appends `ALARM_SCHEDULED` and returns the id of the event, which the arming context is keyed by (ADR 0070). */
    fun scheduled(occurrenceId: String): String {
        val id = newId()
        events.insert(AlarmEvents.scheduled(id, occurrenceId, clock.now()))
        return id
    }

    /** Appends `ALARM_FIRED` and returns the id of the event, which the device telemetry row is keyed by. */
    fun fired(occurrenceId: String): String {
        val id = newId()
        events.insert(AlarmEvents.fired(id, occurrenceId, clock.now()))
        return id
    }

    /**
     * Appends `SNOOZE_ENDED` and returns the id of the event. A snooze's alarm fired: it is not a ladder rung, so it
     * is not `ALARM_FIRED` and consumes none (ADR 0066).
     */
    fun snoozeEnded(occurrenceId: String): String {
        val id = newId()
        events.insert(AlarmEvents.snoozeEnded(id, occurrenceId, clock.now()))
        return id
    }

    fun watchdogRepair(occurrenceId: String) =
        events.insert(AlarmEvents.watchdogRepair(newId(), occurrenceId, clock.now()))
}
