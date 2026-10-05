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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Golden scenario 8: timezone travel (ADR 0068), asserted in both directions, over the real repositories. A daily 08:00
 * template was set up in Kolkata (UTC+5:30), so the dose on 2026-01-10 is due at 02:30 UTC. The device then moves.
 *
 * East (Tokyo, UTC+9): 08:00 there is 23:00 UTC the day before, already in the past at the change, so the dose is due
 * at the change and not missed. West (New York, UTC-5): 08:00 there is 13:00 UTC, so the dose moves later.
 */
class TimeZoneChangeCommandTest {
    private lateinit var driver: SqlDriver
    private lateinit var templates: ScheduleTemplateRepository
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var events: EventRepository
    private lateinit var change: TimeZoneChangeCommand
    private lateinit var materialise: MaterialiseCommand
    private lateinit var reconcile: ReconcileCommand
    private var ids = 0
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val newYork = TimeZone.of("America/New_York")
    private val date = LocalDate(2026, 1, 10)
    private val dueInKolkata = Instant.parse("2026-01-10T02:30:00Z")
    private val tokyoEight = Instant.parse("2026-01-09T23:00:00Z")
    private val newYorkEight = Instant.parse("2026-01-10T13:00:00Z")

    // 01:30 UTC: the Kolkata dose is not yet due, and the Tokyo wall clock time for it is long past.
    private val changedAt = Instant.parse("2026-01-10T01:30:00Z")

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        val database = MomTimeDatabase(driver)
        SqlDelightPregnancyRepository(database).insert(Pregnancy("preg", PregnancyPhase.PRENATAL, epoch, epoch))
        templates = SqlDelightScheduleTemplateRepository(database)
        events = SqlDelightEventRepository(database)
        occurrences = SqlDelightOccurrenceRepository(database, events)
        val newId = { "id-${ids++}" }
        change = TimeZoneChangeCommand(templates, occurrences, SqlDelightTransactor(database))
        materialise = MaterialiseCommand(templates, occurrences, newId)
        reconcile = ReconcileCommand(occurrences, templates, events, newId)
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
        timeZoneId = kolkata,
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
        at: Instant = dueInKolkata,
    ) = Occurrence(id, templateId, date, at, kolkata, state, slot, Criticality.CRITICAL).also(occurrences::insert)

    private fun seed(
        id: String,
        criticality: Criticality = Criticality.CRITICAL,
        slot: Int = 1,
        state: OccurrenceState = OccurrenceState.PENDING,
        at: Instant = dueInKolkata,
    ): Occurrence {
        templates.insert(template("t-$id", criticality))
        return occurrence(id, "t-$id", slot, state, at)
    }

    private fun scheduled(id: String) = checkNotNull(occurrences.findById(id)).scheduledInstant

    // --- east

    @Test
    fun `east a dose not yet due becomes due at the change and is not missed`() {
        seed("a")

        val result = change.dispatch(tokyo, changedAt)

        assertEquals(ZoneChangeResult(templatesMoved = 1, occurrencesMoved = 1), result)
        withMessage("due at the change", changedAt, scheduled("a"))
        assertEquals(tokyo, checkNotNull(occurrences.findById("a")).timeZoneId)
        withMessage("not missed when Reconcile runs at the change", 0, reconcile.dispatch(changedAt))
        assertEquals(OccurrenceState.PENDING, checkNotNull(occurrences.findById("a")).state)
        assertEquals(0, events.findByOccurrenceAndType("a", EventType.MISSED).size)
    }

    // The unclamped alternative, for contrast: the Tokyo wall clock time is 23:00 UTC, whose two hour grace ended at
    // 01:00 UTC, before the change. Moving there would have made the dose MISSED by flying east.
    @Test
    fun `the unclamped time would already have been past its grace`() {
        assertEquals(tokyoEight + 2.hours, Instant.parse("2026-01-10T01:00:00Z"))
        assertEquals(true, tokyoEight + 2.hours < changedAt)
    }

    // --- west

    @Test
    fun `west a dose moves later, to its new wall clock time`() {
        seed("a")

        change.dispatch(newYork, changedAt)

        assertEquals(newYorkEight, scheduled("a"))
        assertEquals(newYork, checkNotNull(occurrences.findById("a")).timeZoneId)
    }

    // --- the other states

    @Test
    fun `an overdue pending occurrence is due now in the east, not made earlier`() {
        // Overdue in Kolkata at 05:00 UTC, within the STANDARD grace of four hours.
        val overdueAt = Instant.parse("2026-01-10T05:00:00Z")
        seed("east", Criticality.STANDARD, slot = 1)

        change.dispatch(tokyo, overdueAt)

        withMessage("east: due now, not made earlier", overdueAt, scheduled("east"))
        assertEquals(0, reconcile.dispatch(overdueAt), "and not missed")
    }

    @Test
    fun `west an overdue pending occurrence moves to its later wall clock time`() {
        seed("a", Criticality.STANDARD)

        change.dispatch(newYork, Instant.parse("2026-01-10T05:00:00Z"))

        assertEquals(newYorkEight, scheduled("a"))
    }

    // A snooze's end is an absolute instant decided when she snoozed. The occurrence moves, the event does not, and
    // nothing is written.
    @Test
    fun `a snoozed occurrence moves and its snooze end does not`() {
        val a = seed("a", state = OccurrenceState.SNOOZED)
        val snoozedUntil = Instant.parse("2026-01-10T01:20:00Z")
        events.insert(
            Event(
                "snz",
                "a",
                EventType.SNOOZED,
                changedAt,
                null,
                EventSource.USER,
                EventPayload.Snooze(1, snoozedUntil),
            ),
        )
        val log = events.findForOccurrence("a")

        change.dispatch(tokyo, changedAt)

        assertEquals(OccurrenceState.SNOOZED, checkNotNull(occurrences.findById("a")).state)
        withMessage("the occurrence moved", changedAt, scheduled("a"))
        withMessage("no event was written or changed", log, events.findForOccurrence("a"))
        val snoozeEnd =
            OccurrenceActionCommand(
                occurrences,
                events,
                SqlDelightAppSettingsRepository(MomTimeDatabase(driver)),
                templates,
                SqlDelightTransactor(MomTimeDatabase(driver)),
            ) {
                "x"
            }.runningSnoozeEnd(checkNotNull(occurrences.findById(a.id)))
        withMessage("the snooze still ends where it was decided to end", snoozedUntil, snoozeEnd)
    }

    @Test
    fun `a terminal occurrence is never touched`() {
        val states = listOf(OccurrenceState.COMPLETED, OccurrenceState.SKIPPED, OccurrenceState.MISSED)
        templates.insert(template("t"))
        states.forEachIndexed { index, state ->
            occurrences.insert(
                Occurrence(
                    "done-$index",
                    "t",
                    LocalDate(2026, 1, 1 + index),
                    dueInKolkata,
                    kolkata,
                    state,
                    10 + index,
                    Criticality.CRITICAL,
                ),
            )
        }
        val before = states.indices.map { occurrences.findById("done-$it") }

        change.dispatch(tokyo, changedAt)

        withMessage(
            "terminal occurrences are exactly as they were",
            before,
            states.indices.map {
                occurrences.findById("done-$it")
            },
        )
        assertEquals(0, change.dispatch(tokyo, changedAt + 1.hours).occurrencesMoved)
    }

    @Test
    fun `a move keeps the id, the slot, the date and the state`() {
        val a = seed("a", slot = 41)

        change.dispatch(tokyo, changedAt)

        val moved = checkNotNull(occurrences.findById("a"))
        assertEquals(a.id, moved.id)
        withMessage("the slot, so the armed alarm's request code still matches", 41, moved.alarmSlot)
        assertEquals(a.localDate, moved.localDate)
        assertEquals(a.templateId, moved.templateId)
        assertEquals(OccurrenceState.PENDING, moved.state)
        assertNotEquals(a.scheduledInstant, moved.scheduledInstant)
        assertEquals(1, occurrences.findForTemplate("t-a").size)
    }

    // --- the template, and what is materialised afterwards

    @Test
    fun `the template follows the device zone and later materialisation uses it`() {
        templates.insert(template("daily"))

        change.dispatch(tokyo, changedAt)
        assertEquals(tokyo, checkNotNull(templates.findById("daily")).timeZoneId)
        // Materialise the next 48 hours from the change: the dates it creates are in Tokyo, at 08:00 there.
        materialise.dispatch(changedAt)

        val created = occurrences.findForTemplate("daily")
        assertEquals(true, created.isNotEmpty())
        withMessage("every occurrence is in the new zone", setOf(tokyo), created.map { it.timeZoneId }.toSet())
        for (o in created) {
            assertEquals(
                LocalDateTime(o.localDate, LocalTime(8, 0)).toInstant(tokyo),
                o.scheduledInstant,
                "08:00 in Tokyo on ${o.localDate}",
            )
        }
    }

    // An inactive template's open occurrences can still ring, so they move too, and every template's zone follows the
    // device, so a template reactivated later is already in her zone (ADR 0068).
    @Test
    fun `an inactive template follows the device zone and its open occurrences move`() {
        templates.insert(template("off", active = false))
        occurrence("o", "off", slot = 5)

        val result = change.dispatch(tokyo, changedAt)

        assertEquals(ZoneChangeResult(templatesMoved = 1, occurrencesMoved = 1), result)
        assertEquals(tokyo, checkNotNull(templates.findById("off")).timeZoneId)
        assertEquals(changedAt, scheduled("o"))
        templates.setActive("off", true)
        withMessage(
            "reactivated, it is already in her zone",
            tokyo,
            checkNotNull(templates.findById("off")).timeZoneId,
        )
    }

    // A second broadcast for the same zone, later, does not move a clamped occurrence again.
    @Test
    fun `dispatching again for the same zone changes nothing`() {
        seed("a")
        change.dispatch(tokyo, changedAt)
        val after = occurrences.findById("a")

        val again = change.dispatch(tokyo, changedAt + 3.hours)

        assertEquals(ZoneChangeResult(0, 0), again)
        assertEquals(after, occurrences.findById("a"))
    }

    // Travelling on moves it again from its new place, with the clamp at the later change.
    @Test
    fun `a second journey moves it again from where it is`() {
        seed("a")
        change.dispatch(newYork, changedAt)
        assertEquals(newYorkEight, scheduled("a"))

        change.dispatch(tokyo, changedAt + 1.hours)

        withMessage(
            "east again: the Tokyo time is long past, so due at this change",
            changedAt + 1.hours,
            scheduled("a"),
        )
    }

    /** `assertEquals` with the message first, which reads better above a long expected value. */
    private fun <T> withMessage(
        message: String,
        expected: T,
        actual: T,
    ) = assertEquals(expected, actual, message)
}
