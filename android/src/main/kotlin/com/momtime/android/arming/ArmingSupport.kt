package com.momtime.android.arming

import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.engine.AlarmEvents
import com.momtime.shared.engine.ArmCandidate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What selection reads: the occurrences that are pending, each with the criticality that decides its ladder
 * and the count of its `ALARM_FIRED` events, which is the record of the rungs that have fired.
 */
internal class ArmCandidates(
    private val occurrences: OccurrenceRepository,
    private val templates: ScheduleTemplateRepository,
    private val events: EventRepository,
) {
    fun pending(): List<ArmCandidate> = occurrences.findPending().map(::of)

    /** The occurrence that owns [slot], or null if none does. */
    fun owning(slot: Int): Occurrence? = occurrences.findByAlarmSlot(slot)

    fun of(occurrence: Occurrence): ArmCandidate =
        ArmCandidate(
            occurrence = occurrence,
            criticality =
                checkNotNull(templates.findById(occurrence.templateId)) {
                    "occurrence ${occurrence.id} has no template"
                }.criticality,
            firedCount = events.countByOccurrenceAndType(occurrence.id, EventType.ALARM_FIRED).toInt(),
        )
}

/** The alarm path's writes to the event log, and its notion of now. The shapes are `AlarmEvents`'. */
internal class AlarmLog(
    private val events: EventRepository,
    private val clock: Clock,
    private val newId: () -> String,
) {
    fun now(): Instant = clock.now()

    fun scheduled(occurrenceId: String) = events.insert(AlarmEvents.scheduled(newId(), occurrenceId, clock.now()))

    fun fired(occurrenceId: String) = events.insert(AlarmEvents.fired(newId(), occurrenceId, clock.now()))

    fun watchdogRepair(occurrenceId: String) =
        events.insert(AlarmEvents.watchdogRepair(newId(), occurrenceId, clock.now()))
}
