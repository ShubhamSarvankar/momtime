package com.momtime.shared.data

import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.engine.OccurrenceMaterialiser
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

interface OccurrenceRepository {
    /**
     * A plain INSERT. A duplicate (template_id, local_date) or alarmSlot throws: the UNIQUE
     * indexes are a guarantee that fails loudly, not something to absorb (ADR 0036). Production
     * materialisation goes through [materialiseWindow], which cannot produce a duplicate.
     */
    fun insert(occurrence: Occurrence)

    /**
     * Materialises one template over [windowStart, windowEnd) as a single atomic operation: read
     * the dates already materialised, compute the missing ones, allocate their alarmSlots and
     * insert them, all in one transaction (ADR 0036). Two overlapping runs therefore serialise,
     * and the loser sees the rows of the winner and excludes them. If any insert throws, the
     * whole batch, including its alarmSlot allocations, rolls back. Returns the occurrences
     * created.
     */
    fun materialiseWindow(
        template: ScheduleTemplate,
        windowStart: Instant,
        windowEnd: Instant,
        generateId: () -> String,
    ): List<Occurrence>

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

    override fun materialiseWindow(
        template: ScheduleTemplate,
        windowStart: Instant,
        windowEnd: Instant,
        generateId: () -> String,
    ): List<Occurrence> =
        database.transactionWithResult {
            val created =
                OccurrenceMaterialiser.materialise(
                    template = template,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    alreadyMaterialisedDates = datesAlreadyMaterialisedForTemplate(template.id),
                    generateId = generateId,
                    allocateSlot = ::allocateNextAlarmSlot,
                )
            created.forEach(::insert)
            created
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
