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
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import com.momtime.shared.engine.EventLogReduction
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** `Reconcile` and materialisation as commands over the repositories (ADR 0030, ADR 0036, invariant 3). */
class CommandsTest {
    private lateinit var driver: SqlDriver
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var templates: ScheduleTemplateRepository
    private lateinit var events: EventRepository
    private lateinit var reconcile: ReconcileCommand
    private lateinit var materialise: MaterialiseCommand
    private var ids = 0
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val zone = TimeZone.UTC
    private val scheduled = Instant.parse("2026-03-01T08:00:00Z")

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        val database = MomTimeDatabase(driver)
        SqlDelightPregnancyRepository(database).insert(Pregnancy("preg", PregnancyPhase.PRENATAL, epoch, epoch))
        templates = SqlDelightScheduleTemplateRepository(database)
        events = SqlDelightEventRepository(database)
        occurrences = SqlDelightOccurrenceRepository(database, events)
        val newId = { "id-${ids++}" }
        reconcile = ReconcileCommand(occurrences, templates, events, newId)
        materialise = MaterialiseCommand(templates, occurrences, newId)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun template(
        id: String,
        criticality: Criticality = Criticality.CRITICAL,
        active: Boolean = true,
    ) = ScheduleTemplate(
        id = id,
        pregnancyId = "preg",
        title = "Iron",
        notes = null,
        taskType = TaskType.SUPPLEMENT,
        criticality = criticality,
        timeOfDay = LocalTime(8, 0),
        timeZoneId = zone,
        recurrence = Recurrence.Daily,
        mission = MissionConfig.None,
        nutritionTags = setOf(NutritionTag.IRON),
        dosage = null,
        doctorInstructions = null,
        inventoryCount = null,
        refillThresholdDays = null,
        active = active,
    )

    private fun occurrence(
        id: String,
        templateId: String,
        slot: Int,
        state: OccurrenceState = OccurrenceState.PENDING,
        day: Int = 1,
        criticality: Criticality = Criticality.CRITICAL,
    ) = Occurrence(id, templateId, LocalDate(2026, 3, day), scheduled, zone, state, slot, criticality)

    private fun event(
        id: String,
        occurrenceId: String,
        type: EventType,
    ) = Event(id, occurrenceId, type, scheduled, null, EventSource.USER, EventPayload.None)

    private fun seed(
        id: String,
        criticality: Criticality = Criticality.CRITICAL,
        state: OccurrenceState = OccurrenceState.PENDING,
        slot: Int = 1,
    ) {
        templates.insert(template("t-$id", criticality))
        occurrences.insert(occurrence(id, "t-$id", slot, state, criticality = criticality))
    }

    @Test
    fun `an occurrence past its grace becomes MISSED with the grace expiry as its effective time`() {
        seed("a")
        assertEquals(0, reconcile.dispatch(scheduled + 2.hours - 1.minutes), "before the grace expires nothing is done")
        assertEquals(1, reconcile.dispatch(scheduled + 5.hours))
        assertEquals(OccurrenceState.MISSED, occurrences.findById("a")?.state)
        val missed = events.findByOccurrenceAndType("a", EventType.MISSED).single()
        assertEquals(scheduled + 2.hours, missed.effectiveAt)
        assertEquals(scheduled + 5.hours, missed.deviceTimestamp)
    }

    @Test
    fun `dispatching again changes nothing`() {
        seed("a")
        reconcile.dispatch(scheduled + 5.hours)
        val log = events.findForOccurrence("a")
        assertEquals(0, reconcile.dispatch(scheduled + 6.hours))
        assertEquals(log, events.findForOccurrence("a"))
    }

    @Test
    fun `a snoozed occurrence past its grace becomes MISSED`() {
        seed("a", state = OccurrenceState.SNOOZED)
        assertEquals(1, reconcile.dispatch(scheduled + 5.hours))
        assertEquals(OccurrenceState.MISSED, occurrences.findById("a")?.state)
    }

    // The state is PENDING but the log says it was completed: a divergent row. Reconcile leaves it alone.
    @Test
    fun `an occurrence with a terminal event is not reconciled`() {
        seed("a")
        events.insert(event("done", "a", EventType.COMPLETED))
        assertEquals(0, reconcile.dispatch(scheduled + 5.hours))
        assertEquals(OccurrenceState.PENDING, occurrences.findById("a")?.state)
        assertTrue(events.findByOccurrenceAndType("a", EventType.MISSED).isEmpty())
    }

    // With foreign keys enforced a template cannot be missing under an occurrence, so this stands in for a read that
    // fails to find it: the pass must carry on with the others and not stop, because it runs every 15 minutes.
    @Test
    fun `an occurrence whose template cannot be read is left alone`() {
        seed("a")
        val blind =
            object : ScheduleTemplateRepository by templates {
                override fun findById(id: String): ScheduleTemplate? = null
            }
        val command = ReconcileCommand(occurrences, blind, events) { "blind-${ids++}" }

        assertEquals(0, command.dispatch(scheduled + 5.hours))
        assertEquals(OccurrenceState.PENDING, occurrences.findById("a")?.state)
    }

    @Test
    fun `a terminal occurrence is never touched`() {
        seed("a", state = OccurrenceState.COMPLETED)
        assertEquals(0, reconcile.dispatch(scheduled + 5.hours))
        assertEquals(OccurrenceState.COMPLETED, occurrences.findById("a")?.state)
    }

    @Test
    fun `open occurrences are the pending and the snoozed`() {
        seed("p", slot = 1)
        seed("s", state = OccurrenceState.SNOOZED, slot = 2)
        seed("c", state = OccurrenceState.COMPLETED, slot = 3)
        assertEquals(setOf("p", "s"), occurrences.findOpen().map { it.id }.toSet())
    }

    /** Withdraws [id] as the edit command will: its WITHDRAWN event and its state, in one transition. */
    private fun withdraw(id: String) =
        occurrences.transition(
            id,
            OccurrenceState.WITHDRAWN,
            Event("withdrawn-$id", id, EventType.WITHDRAWN, scheduled, null, EventSource.USER, EventPayload.None),
        )

    // The query, on its own (ADR 0079): a withdrawn occurrence is not open, so Reconcile never reads it.
    @Test
    fun `findOpen never returns a withdrawn occurrence`() {
        seed("p", slot = 1)
        seed("s", state = OccurrenceState.SNOOZED, slot = 2)
        seed("w", slot = 3)
        withdraw("w")
        seed("w2", state = OccurrenceState.WITHDRAWN, slot = 4)

        assertEquals(listOf("p", "s"), occurrences.findOpen().map { it.id }.sorted())
    }

    // The event level record, on its own: the row still says PENDING (a divergent row), and the WITHDRAWN event
    // in its log is what closes it. Reconcile leaves it alone, as it does for the other four closing events.
    @Test
    fun `an occurrence with a withdrawal event is not reconciled`() {
        seed("a")
        events.insert(event("gone", "a", EventType.WITHDRAWN))

        assertEquals(0, reconcile.dispatch(scheduled + 5.hours))

        assertEquals(OccurrenceState.PENDING, occurrences.findById("a")?.state)
        assertTrue(events.findByOccurrenceAndType("a", EventType.MISSED).isEmpty())
    }

    // A withdrawn occurrence (its event and state written here, as no writer exists yet) beside one completed, one
    // skipped and one missed. Reconcile is then dispatched a day after the withdrawn occurrence's grace has ended:
    // the step that would write MISSED for it if anything still treated it as open. The figures are read after.
    @Test
    fun `a withdrawn occurrence is in none of the three figures, even after its grace`() {
        seed("w", slot = 1)
        seed("c", slot = 2)
        seed("s", slot = 3)
        seed("m", slot = 4)
        withdraw("w")
        occurrences.transition("c", OccurrenceState.COMPLETED, event("done", "c", EventType.COMPLETED))
        occurrences.transition("s", OccurrenceState.SKIPPED, event("skip", "s", EventType.SKIPPED))
        assertEquals(1, reconcile.dispatch(scheduled + 2.hours), "only the untouched occurrence is missed")
        assertEquals(OccurrenceState.MISSED, occurrences.findById("m")?.state)

        val dayAfterGrace = scheduled + 2.hours + 24.hours
        assertEquals(0, reconcile.dispatch(dayAfterGrace))

        assertTrue(events.findByOccurrenceAndType("w", EventType.MISSED).isEmpty(), "no MISSED for the withdrawn one")
        assertEquals(listOf(EventType.WITHDRAWN), events.findForOccurrence("w").map { it.eventType })
        assertEquals(OccurrenceState.WITHDRAWN, occurrences.findById("w")?.state)
        val all = listOf("w", "c", "s", "m")
        assertEquals(
            EventLogReduction.AdherenceFigures(completed = 1, missed = 1, skipped = 1),
            EventLogReduction.adherenceFigures(
                all.map { checkNotNull(occurrences.findById(it)) },
                all.flatMap { events.findForOccurrence(it) },
                asOf = dayAfterGrace,
            ),
        )
    }

    @Test
    fun `materialising covers 48 hours and a second run adds nothing`() {
        templates.insert(template("daily"))
        // Daily at 08:00 over [00:00 on the 1st, 00:00 on the 3rd) is the 1st and the 2nd.
        val start = Instant.parse("2026-03-01T00:00:00Z")
        assertEquals(2, materialise.dispatch(start))
        assertEquals(0, materialise.dispatch(start))
        assertEquals(2, occurrences.findForTemplate("daily").size)
    }

    @Test
    fun `an inactive template materialises nothing`() {
        templates.insert(template("off", active = false))
        assertEquals(0, materialise.dispatch(Instant.parse("2026-03-01T00:00:00Z")))
        assertEquals(emptyList(), templates.findAllActive().filter { it.id == "off" })
    }
}
