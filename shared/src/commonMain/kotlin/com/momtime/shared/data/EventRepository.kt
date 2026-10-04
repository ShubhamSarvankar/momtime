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
        val columns = event.payload.toColumns()
        database.eventQueries.insertEvent(
            id = event.id,
            occurrence_id = event.occurrenceId,
            event_type = event.eventType.name,
            device_timestamp = event.deviceTimestamp.toDb(),
            effective_at = event.effectiveAt.toDbOrNull(),
            source = event.source.name,
            snooze_number = columns.snoozeNumber,
            mission_result_type = columns.missionType,
            water_ml = columns.waterMl,
            weight_grams = columns.weightGrams,
            caregiver_link_id = columns.caregiverLinkId,
            canary_scheduled_at = columns.canaryScheduledAt,
            canary_actual_at = columns.canaryActualAt,
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
        val canaryScheduledAt: Long? = null,
        val canaryActualAt: Long? = null,
    )

    private fun EventPayload.toColumns(): PayloadColumns =
        when (this) {
            is EventPayload.None -> PayloadColumns(null, null, null, null, null)
            is EventPayload.Snooze -> PayloadColumns(snoozeNumber.toLong(), null, null, null, null)
            is EventPayload.MissionResult -> PayloadColumns(null, missionType.name, null, null, null)
            is EventPayload.Water -> PayloadColumns(null, null, waterMl.toLong(), null, null)
            is EventPayload.Weight -> PayloadColumns(null, null, null, weightGrams.toLong(), null)
            is EventPayload.CaregiverReference -> PayloadColumns(null, null, null, null, caregiverLinkId)
            is EventPayload.Canary ->
                PayloadColumns(
                    null,
                    null,
                    null,
                    null,
                    null,
                    canaryScheduledAt = scheduledAt.toDb(),
                    canaryActualAt = actualAt.toDbOrNull(),
                )
        }

    private fun com.momtime.shared.data.Event.toDomain(): Event {
        val type = EventType.valueOf(event_type)
        // The payload columns are sparse and the table ties none of them to a type, so a mapping bug could set
        // any column on any event. Each type has its own set of allowed columns (EventColumn.kt) and a row that
        // sets another fails loudly here, rather than decoding as a payload nobody wrote (ADR 0052).
        val set =
            buildSet {
                if (effective_at != null) add(EventColumn.EFFECTIVE_AT)
                if (snooze_number != null) add(EventColumn.SNOOZE_NUMBER)
                if (mission_result_type != null) add(EventColumn.MISSION_RESULT_TYPE)
                if (water_ml != null) add(EventColumn.WATER_ML)
                if (weight_grams != null) add(EventColumn.WEIGHT_GRAMS)
                if (caregiver_link_id != null) add(EventColumn.CAREGIVER_LINK_ID)
                if (canary_scheduled_at != null) add(EventColumn.CANARY_SCHEDULED_AT)
                if (canary_actual_at != null) add(EventColumn.CANARY_ACTUAL_AT)
            }
        val unexpected = set - allowedColumns(type)
        check(unexpected.isEmpty()) { "columns $unexpected are set on a $type event ($id)" }
        // A canary with an actual instant and no scheduled one was never written by the app. One with neither
        // is the old form and decodes as no payload.
        check(canary_actual_at == null || canary_scheduled_at != null) {
            "a CANARY_RESULT has an actual instant but no scheduled one ($id)"
        }
        val payload: EventPayload =
            when {
                snooze_number != null -> EventPayload.Snooze(snooze_number.toInt())
                mission_result_type != null ->
                    EventPayload.MissionResult(MissionResultType.valueOf(mission_result_type))
                water_ml != null -> EventPayload.Water(water_ml.toInt())
                weight_grams != null -> EventPayload.Weight(weight_grams.toInt())
                caregiver_link_id != null -> EventPayload.CaregiverReference(caregiver_link_id)
                canary_scheduled_at != null ->
                    EventPayload.Canary(canary_scheduled_at.toInstant(), canary_actual_at.toInstantOrNull())
                else -> EventPayload.None
            }
        return Event(
            id = id,
            occurrenceId = occurrence_id,
            eventType = type,
            deviceTimestamp = device_timestamp.toInstant(),
            effectiveAt = effective_at.toInstantOrNull(),
            source = EventSource.valueOf(source),
            payload = payload,
        )
    }
}
