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
import com.momtime.shared.engine.OccurrenceAction
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Her three actions as a command to the domain (ADR 0066, invariant 3). Each test reads the exact event log and the
 * exact state before and after, so "and nothing else" can fail: a stray event, a second state change or a missing
 * one is a difference.
 */
class OccurrenceActionCommandTest {
    private lateinit var driver: SqlDriver
    private lateinit var occurrences: OccurrenceRepository
    private lateinit var events: EventRepository
    private lateinit var settings: AppSettingsRepository
    private lateinit var command: OccurrenceActionCommand
    private var ids = 0
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val scheduled = Instant.parse("2026-03-01T08:00:00Z")
    private val now = scheduled + 1.minutes

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        val database = MomTimeDatabase(driver)
        SqlDelightPregnancyRepository(database).insert(Pregnancy("preg", PregnancyPhase.PRENATAL, epoch, epoch))
        val templates = SqlDelightScheduleTemplateRepository(database)
        events = SqlDelightEventRepository(database)
        occurrences = SqlDelightOccurrenceRepository(database, events)
        settings = SqlDelightAppSettingsRepository(database)
        settings.ensureSeeded()
        command = OccurrenceActionCommand(occurrences, events, settings) { "id-${ids++}" }
        templates.insert(template())
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun template() =
        ScheduleTemplate(
            id = "t",
            pregnancyId = "preg",
            title = "Iron",
            notes = null,
            taskType = TaskType.SUPPLEMENT,
            criticality = Criticality.CRITICAL,
            timeOfDay = LocalTime(8, 0),
            timeZoneId = TimeZone.UTC,
            recurrence = Recurrence.Daily,
            mission = MissionConfig.None,
            nutritionTags = setOf(NutritionTag.IRON),
            dosage = null,
            doctorInstructions = null,
            inventoryCount = null,
            refillThresholdDays = null,
            active = true,
        )

    private fun occurrence(
        id: String,
        at: Instant = scheduled,
        slot: Int,
        state: OccurrenceState = OccurrenceState.PENDING,
    ) = Occurrence(id, "t", LocalDate(2026, 3, slot), at, TimeZone.UTC, state, slot)
        .also(occurrences::insert)

    private fun log(id: String) = events.findForOccurrence(id).map { Triple(it.eventType, it.source, it.payload) }

    private fun state(id: String) = occurrences.findById(id)?.state

    private fun snoozedBefore(
        id: String,
        count: Int,
    ) = repeat(count) { index ->
        events.insert(
            Event(
                "snz-$id-$index",
                id,
                EventType.SNOOZED,
                now,
                null,
                EventSource.USER,
                EventPayload.Snooze(index + 1, now + 10.minutes),
            ),
        )
    }

    @Test
    fun `acknowledge writes COMPLETED and completes, and nothing else`() {
        occurrence("a", slot = 1)
        val before = log("a")

        assertEquals(ActionResult.Done, command.dispatch("a", OccurrenceAction.ACKNOWLEDGE, now))

        assertEquals(
            before + Triple(EventType.COMPLETED, EventSource.USER, EventPayload.None),
            log("a"),
            "exactly one new event",
        )
        assertEquals(OccurrenceState.COMPLETED, state("a"))
        assertEquals(now, events.findByOccurrenceAndType("a", EventType.COMPLETED).single().deviceTimestamp)
    }

    @Test
    fun `skip writes SKIPPED and skips, and nothing else`() {
        occurrence("a", slot = 1)
        val before = log("a")

        assertEquals(ActionResult.Done, command.dispatch("a", OccurrenceAction.SKIP, now))

        assertEquals(before + Triple(EventType.SKIPPED, EventSource.USER, EventPayload.None), log("a"))
        assertEquals(OccurrenceState.SKIPPED, state("a"))
    }

    @Test
    fun `snooze writes SNOOZED carrying its number and snoozes, and nothing else`() {
        occurrence("a", slot = 1)
        val before = log("a")

        assertEquals(ActionResult.Done, command.dispatch("a", OccurrenceAction.SNOOZE, now))

        assertEquals(
            before + Triple(EventType.SNOOZED, EventSource.USER, EventPayload.Snooze(1, now + 10.minutes)),
            log("a"),
        )
        assertEquals(OccurrenceState.SNOOZED, state("a"))
    }

    @Test
    fun `an action touches no other occurrence`() {
        occurrence("a", slot = 1)
        occurrence("b", at = scheduled + 1.hours, slot = 2)
        val untouched = log("b")

        command.dispatch("a", OccurrenceAction.ACKNOWLEDGE, now)

        assertEquals(untouched, log("b"))
        assertEquals(OccurrenceState.PENDING, state("b"))
    }

    @Test
    fun `the snooze number counts up and the third is the last`() {
        occurrence("a", slot = 1)
        repeat(3) { assertEquals(ActionResult.Done, command.dispatch("a", OccurrenceAction.SNOOZE, now)) }
        assertEquals(
            listOf(1, 2, 3),
            events
                .findByOccurrenceAndType(
                    "a",
                    EventType.SNOOZED,
                ).map { (it.payload as EventPayload.Snooze).snoozeNumber },
        )
        val before = log("a")

        assertEquals(ActionResult.NotAvailable, command.dispatch("a", OccurrenceAction.SNOOZE, now))
        assertEquals(before, log("a"), "a refused snooze writes nothing")
        assertEquals(setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SKIP), command.available("a", now))
    }

    // Golden scenario 4 through the command: a snooze that would end at or after the next occurrence of the same
    // template is refused and writes nothing.
    @Test
    fun `a snooze past the next occurrence of the same template is refused`() {
        occurrence("a", slot = 1)
        occurrence("b", at = now + 10.minutes, slot = 2)
        val before = log("a")

        assertEquals(ActionResult.NotAvailable, command.dispatch("a", OccurrenceAction.SNOOZE, now))

        assertEquals(before, log("a"))
        assertEquals(OccurrenceState.PENDING, state("a"))
        assertEquals(setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SKIP), command.available("a", now))
        // One minute earlier it fits.
        assertEquals(ActionResult.Done, command.dispatch("a", OccurrenceAction.SNOOZE, now - 1.minutes))
    }

    @Test
    fun `her snooze duration setting decides whether it fits`() {
        occurrence("a", slot = 1)
        occurrence("b", at = now + 30.minutes, slot = 2)
        assertEquals(3, command.available("a", now).size)

        settings.updateSnoozeDurationMinutes(45)

        assertEquals(setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SKIP), command.available("a", now))
    }

    @Test
    fun `a terminal occurrence refuses every action and writes nothing`() {
        for ((index, state) in listOf(
            OccurrenceState.COMPLETED,
            OccurrenceState.SKIPPED,
            OccurrenceState.MISSED,
        ).withIndex()) {
            val id = "t$index"
            occurrence(id, slot = index + 1, state = state)
            for (action in OccurrenceAction.entries) {
                assertEquals(ActionResult.NotAvailable, command.dispatch(id, action, now), "$state $action")
            }
            assertEquals(emptyList(), log(id))
            assertEquals(state, state(id))
        }
    }

    @Test
    fun `a snoozed occurrence can still be acknowledged or skipped`() {
        occurrence("a", slot = 1, state = OccurrenceState.SNOOZED)
        snoozedBefore("a", 1)
        assertEquals(ActionResult.Done, command.dispatch("a", OccurrenceAction.ACKNOWLEDGE, now))
        assertEquals(OccurrenceState.COMPLETED, state("a"))
        occurrence("b", slot = 2, state = OccurrenceState.SNOOZED)
        snoozedBefore("b", 1)
        assertEquals(ActionResult.Done, command.dispatch("b", OccurrenceAction.SKIP, now))
        assertEquals(OccurrenceState.SKIPPED, state("b"))
    }

    // A divergent row: the state says SNOOZED and the log has no SNOOZED event. There is no snooze to end.
    @Test
    fun `a snoozed state with no snooze event has no running snooze`() {
        val a = occurrence("a", slot = 1, state = OccurrenceState.SNOOZED)

        assertNull(command.runningSnoozeEnd(a))
    }

    // The other divergent row: a SNOOZED event that records no end (nothing the app wrote, and none a migration
    // leaves). It decodes with no payload, so there is no end to read and no snooze to arm.
    @Test
    fun `a SNOOZED event with no recorded end has no running snooze`() {
        val a = occurrence("a", slot = 1, state = OccurrenceState.SNOOZED)
        driver.execute(
            null,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source, snooze_number) " +
                "VALUES ('bare', 'a', 'SNOOZED', 0, 'USER', 1)",
            0,
        )

        assertEquals(EventPayload.None, events.findById("bare")?.payload)
        assertNull(command.runningSnoozeEnd(a))
    }

    @Test
    fun `an unknown occurrence is reported and nothing is written`() {
        assertEquals(ActionResult.UnknownOccurrence, command.dispatch("nope", OccurrenceAction.ACKNOWLEDGE, now))
        assertEquals(emptySet(), command.available("nope", now))
    }

    // A snooze is running from the SNOOZED event until a SNOOZE_ENDED has been written for it, and its end is the
    // one recorded in the event.
    @Test
    fun `the running snooze is the end recorded when she snoozed`() {
        val a = occurrence("a", slot = 1)
        assertNull(command.runningSnoozeEnd(a), "not snoozed")

        command.dispatch("a", OccurrenceAction.SNOOZE, now)
        val snoozed = checkNotNull(occurrences.findById("a"))
        assertEquals(now + 10.minutes, command.runningSnoozeEnd(snoozed))
        assertEquals(
            now + 10.minutes,
            (events.findByOccurrenceAndType("a", EventType.SNOOZED).single().payload as EventPayload.Snooze)
                .snoozedUntil,
            "the end is recorded in the event itself",
        )

        events.insert(
            Event("end", "a", EventType.SNOOZE_ENDED, now + 10.minutes, null, EventSource.SYSTEM, EventPayload.None),
        )
        assertNull(command.runningSnoozeEnd(snoozed), "it has ended")

        // A second snooze is running again until it too has ended, and ends where it was recorded to end.
        command.dispatch("a", OccurrenceAction.SNOOZE, now + 20.minutes)
        assertEquals(now + 30.minutes, command.runningSnoozeEnd(snoozed))
    }

    // Her setting is read once, when she snoozes. Changing it while a snooze runs does not move that snooze, and
    // the next snooze takes the new duration.
    @Test
    fun `changing the snooze setting mid snooze does not move the running snooze`() {
        occurrence("a", slot = 1)
        command.dispatch("a", OccurrenceAction.SNOOZE, now)
        val snoozed = checkNotNull(occurrences.findById("a"))
        assertEquals(now + 10.minutes, command.runningSnoozeEnd(snoozed))

        settings.updateSnoozeDurationMinutes(25)

        assertEquals(now + 10.minutes, command.runningSnoozeEnd(snoozed), "the snooze already taken keeps its end")
        events.insert(
            Event("end", "a", EventType.SNOOZE_ENDED, now + 10.minutes, null, EventSource.SYSTEM, EventPayload.None),
        )
        command.dispatch("a", OccurrenceAction.SNOOZE, now + 11.minutes)
        assertEquals(now + 36.minutes, command.runningSnoozeEnd(snoozed), "the next one takes the new duration")
    }
}
