package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import com.momtime.shared.engine.EventLogReduction
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * The real repositories and commands over one in memory database, for the template edit tests. Every command here is
 * the production one, built directly (no Koin): the tests reach their subject through `EditTemplateCommand`,
 * `ReconcileCommand`, `MaterialiseCommand` and `OccurrenceActionCommand`, never through a helper that writes a row.
 */
internal class TemplateEditFixture(
    wrapOccurrences: (OccurrenceRepository) -> OccurrenceRepository = { it },
) {
    val zone: TimeZone = TimeZone.of("Asia/Kolkata")
    val driver: SqlDriver = JvmDatabaseDriverFactory.inMemory().createDriver()
    val database = MomTimeDatabase(driver)
    val templates: ScheduleTemplateRepository = SqlDelightScheduleTemplateRepository(database)
    val events: EventRepository = SqlDelightEventRepository(database)
    val realOccurrences: OccurrenceRepository = SqlDelightOccurrenceRepository(database, events)
    val occurrences: OccurrenceRepository = wrapOccurrences(realOccurrences)
    val transactor: Transactor = SqlDelightTransactor(database)
    private var ids = 0
    val newId: () -> String = { "id-${ids++}" }
    val edit = EditTemplateCommand(templates, occurrences, events, transactor, newId)
    val create = CreateTemplateCommand(templates)
    val materialise = MaterialiseCommand(templates, realOccurrences, newId)
    val reconcile = ReconcileCommand(realOccurrences, templates, events, newId)
    val actions: OccurrenceActionCommand

    init {
        val epoch = Instant.fromEpochMilliseconds(0)
        SqlDelightPregnancyRepository(database).insert(Pregnancy("preg", PregnancyPhase.PRENATAL, epoch, epoch))
        val settings = SqlDelightAppSettingsRepository(database)
        settings.ensureSeeded()
        actions = OccurrenceActionCommand(realOccurrences, events, settings, templates, transactor, newId)
    }

    fun close() = driver.close()

    fun template(
        id: String,
        time: LocalTime = LocalTime(8, 0),
        criticality: Criticality = Criticality.STANDARD,
        recurrence: Recurrence = Recurrence.Daily,
        active: Boolean = true,
    ) = ScheduleTemplate(
        id = id,
        pregnancyId = "preg",
        title = "Iron tablet",
        notes = null,
        taskType = TaskType.SUPPLEMENT,
        criticality = criticality,
        timeOfDay = time,
        timeZoneId = zone,
        recurrence = recurrence,
        mission = MissionConfig.None,
        nutritionTags = setOf(NutritionTag.IRON),
        dosage = null,
        doctorInstructions = null,
        inventoryCount = null,
        refillThresholdDays = null,
        active = active,
    )

    /** [date] at [hour]:[minute] in the template zone. */
    fun at(
        date: LocalDate,
        hour: Int,
        minute: Int = 0,
    ): Instant = LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone)

    /** Inserts [template] and materialises it over [from, to) in one atomic run, as the daily job does. */
    fun insertAndMaterialise(
        template: ScheduleTemplate,
        from: Instant,
        to: Instant,
    ): List<Occurrence> {
        templates.insert(template)
        return occurrences.materialiseWindow(template, from, to, newId)
    }

    fun fire(
        occurrenceId: String,
        at: Instant,
    ) = events.insert(
        Event(newId(), occurrenceId, EventType.ALARM_FIRED, at, null, EventSource.SYSTEM, EventPayload.None),
    )

    fun allOccurrences(): List<Occurrence> = templates.findAll().flatMap { occurrences.findForTemplate(it.id) }

    /** Every event of every occurrence, in a fixed order: the event table, for the tests that write no event-less row. */
    fun allEvents(): List<Event> = allOccurrences().flatMap { events.findForOccurrence(it.id) }.sortedBy { it.id }

    fun figures(asOf: Instant) = EventLogReduction.adherenceFigures(allOccurrences(), allEvents(), asOf)
}
