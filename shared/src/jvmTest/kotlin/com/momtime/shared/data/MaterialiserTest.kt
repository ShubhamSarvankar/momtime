package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What schema version 6 asks of materialisation (ADR 0079), through `MaterialiseCommand` and the repositories it
 * runs over: an occurrence takes its template's criticality, and a date whose occurrence was withdrawn is
 * materialised again with a new id and a new slot.
 */
class MaterialiserTest {
    private lateinit var driver: SqlDriver
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var templates: ScheduleTemplateRepository
    private lateinit var materialise: MaterialiseCommand
    private var ids = 0
    private val epoch = Instant.fromEpochMilliseconds(0)

    // Daily at 08:00 UTC over [00:00 on the 1st, 00:00 on the 3rd) is the 1st and the 2nd.
    private val start = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        val database = MomTimeDatabase(driver)
        SqlDelightPregnancyRepository(database).insert(Pregnancy("preg", PregnancyPhase.PRENATAL, epoch, epoch))
        templates = SqlDelightScheduleTemplateRepository(database)
        occurrences = SqlDelightOccurrenceRepository(database)
        materialise = MaterialiseCommand(templates, occurrences) { "id-${ids++}" }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun template(
        id: String,
        criticality: Criticality,
    ) = ScheduleTemplate(
        id = id,
        pregnancyId = "preg",
        title = "Iron",
        notes = null,
        taskType = TaskType.SUPPLEMENT,
        criticality = criticality,
        timeOfDay = LocalTime(8, 0),
        timeZoneId = TimeZone.UTC,
        recurrence = Recurrence.Daily,
        mission = MissionConfig.None,
        nutritionTags = emptySet(),
        dosage = null,
        doctorInstructions = null,
        inventoryCount = null,
        refillThresholdDays = null,
        active = true,
    )

    @Test
    fun `an occurrence takes its template's criticality`() {
        for (criticality in Criticality.entries) templates.insert(template("t-$criticality", criticality))

        assertEquals(6, materialise.dispatch(start))

        for (criticality in Criticality.entries) {
            val stored = occurrences.findForTemplate("t-$criticality")
            assertEquals(2, stored.size, "$criticality")
            assertEquals(listOf(criticality, criticality), stored.map { it.criticality }, "read back from the table")
        }
    }

    @Test
    fun `withdrawn dates are materialised again`() {
        templates.insert(template("t", Criticality.STANDARD))
        assertEquals(2, materialise.dispatch(start))
        val earlier = occurrences.findForTemplate("t")
        val second = LocalDate(2026, 3, 2)
        val unwanted = earlier.single { it.localDate == second }

        // No writer of WITHDRAWN exists yet (the edit command is a later pull request): the test withdraws the row.
        occurrences.transition(
            unwanted.id,
            OccurrenceState.WITHDRAWN,
            Event("withdrawn", unwanted.id, EventType.WITHDRAWN, start, null, EventSource.USER, EventPayload.None),
        )

        assertEquals(1, materialise.dispatch(start), "the withdrawn date, and only it, is materialised again")

        val all = occurrences.findForTemplate("t")
        assertEquals(3, all.size)
        val added = all.single { row -> earlier.none { it.id == row.id } }
        assertEquals(second, added.localDate)
        assertEquals(OccurrenceState.PENDING, added.state)
        assertEquals(unwanted.scheduledInstant, added.scheduledInstant)
        assertTrue(
            added.alarmSlot > earlier.maxOf { it.alarmSlot },
            "slot ${added.alarmSlot} is not above every earlier slot ${earlier.map { it.alarmSlot }}",
        )
        assertEquals(OccurrenceState.WITHDRAWN, occurrences.findById(unwanted.id)?.state, "the withdrawn row stays")
        assertEquals(1, all.count { it.localDate == LocalDate(2026, 3, 1) }, "the other date is left alone")

        assertEquals(0, materialise.dispatch(start), "and a further run adds nothing")
    }
}
