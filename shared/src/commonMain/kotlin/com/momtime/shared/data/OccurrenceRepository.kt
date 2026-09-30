package com.momtime.shared.data

import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

interface OccurrenceRepository {
    /** Idempotent — INSERT OR IGNORE against the (template_id, local_date) UNIQUE index. */
    fun insert(occurrence: Occurrence)

    fun findById(id: String): Occurrence?

    fun findByTemplateAndDate(
        templateId: String,
        date: LocalDate,
    ): Occurrence?

    fun datesAlreadyMaterialisedForTemplate(templateId: String): Set<LocalDate>

    fun findPending(): List<Occurrence>

    fun findForTemplate(templateId: String): List<Occurrence>

    fun findInWindow(
        from: Instant,
        to: Instant,
    ): List<Occurrence>

    fun updateState(
        id: String,
        state: OccurrenceState,
    )

    /** Allocates the next monotonic alarmSlot, transactionally (ADR 0018/0031). */
    fun allocateNextAlarmSlot(): Int
}

class SqlDelightOccurrenceRepository(
    private val database: MomTimeDatabase,
) : OccurrenceRepository {
    override fun insert(occurrence: Occurrence) {
        database.occurrenceQueries.insertOccurrence(
            id = occurrence.id,
            template_id = occurrence.templateId,
            local_date = occurrence.localDate.toDb(),
            scheduled_instant = occurrence.scheduledInstant.toDb(),
            time_zone_id = occurrence.timeZoneId.toDb(),
            state = occurrence.state.name,
            alarm_slot = occurrence.alarmSlot.toLong(),
        )
    }

    override fun findById(id: String): Occurrence? =
        database.occurrenceQueries
            .selectOccurrenceById(id)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun findByTemplateAndDate(
        templateId: String,
        date: LocalDate,
    ): Occurrence? =
        database.occurrenceQueries
            .selectOccurrenceByTemplateAndDate(templateId, date.toDb())
            .executeAsOneOrNull()
            ?.toDomain()

    override fun datesAlreadyMaterialisedForTemplate(templateId: String): Set<LocalDate> =
        database.occurrenceQueries
            .selectOccurrencesForTemplate(templateId)
            .executeAsList()
            .map { it.local_date.toLocalDate() }
            .toSet()

    override fun findPending(): List<Occurrence> =
        database.occurrenceQueries
            .selectPendingOccurrences()
            .executeAsList()
            .map { it.toDomain() }

    override fun findForTemplate(templateId: String): List<Occurrence> =
        database.occurrenceQueries
            .selectOccurrencesForTemplate(templateId)
            .executeAsList()
            .map { it.toDomain() }

    override fun findInWindow(
        from: Instant,
        to: Instant,
    ): List<Occurrence> =
        database.occurrenceQueries.selectOccurrencesInWindow(from.toDb(), to.toDb()).executeAsList().map {
            it.toDomain()
        }

    override fun updateState(
        id: String,
        state: OccurrenceState,
    ) {
        database.occurrenceQueries.updateOccurrenceState(state.name, id)
    }

    override fun allocateNextAlarmSlot(): Int =
        database.transactionWithResult {
            database.alarmSlotCounterQueries.seedAlarmSlotCounter()
            val next = database.alarmSlotCounterQueries.selectNextAlarmSlot().executeAsOne()
            database.alarmSlotCounterQueries.advanceAlarmSlotCounter()
            next.toInt()
        }

    private fun com.momtime.shared.data.Occurrence.toDomain() =
        Occurrence(
            id = id,
            templateId = template_id,
            localDate = local_date.toLocalDate(),
            scheduledInstant = scheduled_instant.toInstant(),
            timeZoneId = time_zone_id.toTimeZone(),
            state = OccurrenceState.valueOf(state),
            alarmSlot = alarm_slot.toInt(),
        )
}
