package com.momtime.shared.data

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionResultType

interface EventRepository {
    fun insert(event: Event)

    fun findById(id: String): Event?

    /** Administrative-path deletion only (ADR 0026) — never called from domain state transitions. */
    fun deleteById(id: String)

    fun findForOccurrence(occurrenceId: String): List<Event>

    fun findByOccurrenceAndType(
        occurrenceId: String,
        type: EventType,
    ): List<Event>

    fun countByOccurrenceAndType(
        occurrenceId: String,
        type: EventType,
    ): Long
}

class SqlDelightEventRepository(
    private val database: MomTimeDatabase,
) : EventRepository {
    override fun insert(event: Event) {
        val (snoozeNumber, missionType, waterMl, weightGrams, caregiverLinkId) = event.payload.toColumns()
        database.eventQueries.insertEvent(
            id = event.id,
            occurrence_id = event.occurrenceId,
            event_type = event.eventType.name,
            device_timestamp = event.deviceTimestamp.toDb(),
            effective_at = event.effectiveAt.toDbOrNull(),
            source = event.source.name,
            snooze_number = snoozeNumber,
            mission_result_type = missionType,
            water_ml = waterMl,
            weight_grams = weightGrams,
            caregiver_link_id = caregiverLinkId,
        )
    }

    override fun findById(id: String): Event? =
        database.eventQueries
            .selectEventById(id)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun deleteById(id: String) {
        database.eventQueries.deleteEventById(id)
    }

    override fun findForOccurrence(occurrenceId: String): List<Event> =
        database.eventQueries
            .selectEventsForOccurrence(occurrenceId)
            .executeAsList()
            .map { it.toDomain() }

    override fun findByOccurrenceAndType(
        occurrenceId: String,
        type: EventType,
    ): List<Event> =
        database.eventQueries.selectEventsByOccurrenceAndType(occurrenceId, type.name).executeAsList().map {
            it.toDomain()
        }

    override fun countByOccurrenceAndType(
        occurrenceId: String,
        type: EventType,
    ): Long = database.eventQueries.countEventsByOccurrenceAndType(occurrenceId, type.name).executeAsOne()

    private data class PayloadColumns(
        val snoozeNumber: Long?,
        val missionType: String?,
        val waterMl: Long?,
        val weightGrams: Long?,
        val caregiverLinkId: String?,
    )

    private fun EventPayload.toColumns(): PayloadColumns =
        when (this) {
            is EventPayload.None -> PayloadColumns(null, null, null, null, null)
            is EventPayload.Snooze -> PayloadColumns(snoozeNumber.toLong(), null, null, null, null)
            is EventPayload.MissionResult -> PayloadColumns(null, missionType.name, null, null, null)
            is EventPayload.Water -> PayloadColumns(null, null, waterMl.toLong(), null, null)
            is EventPayload.Weight -> PayloadColumns(null, null, null, weightGrams.toLong(), null)
            is EventPayload.CaregiverReference -> PayloadColumns(null, null, null, null, caregiverLinkId)
        }

    private fun com.momtime.shared.data.Event.toDomain(): Event {
        val payload: EventPayload =
            when {
                snooze_number != null -> EventPayload.Snooze(snooze_number.toInt())
                mission_result_type != null ->
                    EventPayload.MissionResult(MissionResultType.valueOf(mission_result_type))
                water_ml != null -> EventPayload.Water(water_ml.toInt())
                weight_grams != null -> EventPayload.Weight(weight_grams.toInt())
                caregiver_link_id != null -> EventPayload.CaregiverReference(caregiver_link_id)
                else -> EventPayload.None
            }
        return Event(
            id = id,
            occurrenceId = occurrence_id,
            eventType = EventType.valueOf(event_type),
            deviceTimestamp = device_timestamp.toInstant(),
            effectiveAt = effective_at.toInstantOrNull(),
            source = EventSource.valueOf(source),
            payload = payload,
        )
    }
}
