package com.momtime.android.delivery

import android.content.Context
import com.momtime.android.arming.FireOutcome
import com.momtime.android.ring.RingSessions
import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.OccurrenceAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Snooze through the one `ensureArmed` entry point (ADR 0066, progress decision 36): a snoozed occurrence is armed
 * by its snooze's end, the watchdog repairs a lost snooze alarm like any other, the snooze's own fire is not a
 * ladder rung, and grace still applies while snoozed.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 33, 36])
class SnoozeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val controller: RingController get() = f.arming.graph.get()
    private val sessions: RingSessions get() = f.arming.graph.get()

    @After
    fun tearDown() = f.close()

    private fun state(id: String) =
        f.arming.occurrences
            .findById(id)
            ?.state

    private fun now() = f.arming.clock.now

    private fun armedAt() =
        f.arming
            .alarms()
            .single()
            .triggerAtMs

    // --- armed through ensureArmed, by the snooze's end

    @Test
    fun `a snoozed occurrence is armed at its snooze end`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.arming.clock.now = a.scheduledInstant + 1.minutes

        controller.act("a", OccurrenceAction.SNOOZE)

        // The CRITICAL ladder's next rung is five minutes in, which is inside the snooze, so it is not what is armed.
        assertEquals((a.scheduledInstant + 11.minutes).toEpochMilliseconds(), armedAt())
        val shape = f.arming.shapes().single()
        assertTrue("exact", shape.alarmClock)
        assertEquals("the request code is the slot", 31, shape.requestCode)
        assertEquals(
            31,
            f.arming.armed
                .current()
                ?.alarmSlot,
        )
        assertEquals(
            a.scheduledInstant + 11.minutes,
            f.arming.armed
                .current()
                ?.rungInstant,
        )
    }

    // My decision (PR 3 review): the watchdog repairs a lost snooze alarm, because the snooze is armed through the
    // same entry point. The alarm is lost as the system loses one, and the watchdog finds positive evidence.
    @Test
    fun `the watchdog repairs a lost snooze alarm`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.arming.clock.now = a.scheduledInstant + 1.minutes
        controller.act("a", OccurrenceAction.SNOOZE)
        f.arming.loseAlarm(31)
        assertEquals("the alarm is gone", 0, f.arming.alarms().size)
        f.arming.clock.now = a.scheduledInstant + 3.minutes

        val result = f.arming.watchdog.run()

        assertTrue("positive evidence: ${result.evidence}", result.evidence.isNotEmpty())
        assertEquals("one repair", 1, f.arming.count(EventType.WATCHDOG_REPAIR, "a"))
        assertEquals("re armed at the snooze end", (a.scheduledInstant + 11.minutes).toEpochMilliseconds(), armedAt())
        assertEquals(31, f.arming.requestCode(f.arming.alarms().single()))
    }

    @Test
    fun `a snooze alarm that is still armed is left alone`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.arming.clock.now = a.scheduledInstant + 1.minutes
        controller.act("a", OccurrenceAction.SNOOZE)
        val log = f.arming.eventLog("a")
        val shapes = f.arming.shapes()
        f.arming.clock.now = a.scheduledInstant + 3.minutes

        val result = f.arming.watchdog.run()

        assertTrue("no evidence: ${result.evidence}", result.evidence.isEmpty())
        assertEquals(log, f.arming.eventLog("a"))
        assertEquals(shapes, f.arming.shapes())
    }

    // --- the snooze's fire is not a rung

    // CRITICAL: RING at t0, RING_REPEAT at t0+5m. Snooze at t0+1m ends at t0+11m. The repeat comes due inside the
    // snooze. The snooze's fire writes SNOOZE_ENDED, not ALARM_FIRED, so the repeat is still the next rung, and it
    // fires after the snooze, as a continuation of the ring the snooze's end started.
    @Test
    fun `the snooze fire consumes no rung and the next rung is still offered`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.arming.clock.now = a.scheduledInstant + 1.minutes
        controller.act("a", OccurrenceAction.SNOOZE)
        val end = a.scheduledInstant + 11.minutes

        val wake = f.fire(a, now = end, rung = end) as FireOutcome.Fired

        assertTrue("it is the snooze's end", wake.rung.afterSnooze)
        assertEquals("SNOOZE_ENDED was written", 1, f.arming.count(EventType.SNOOZE_ENDED, "a"))
        assertEquals("and no ALARM_FIRED for it", 1, f.arming.count(EventType.ALARM_FIRED, "a"))
        assertEquals("the occurrence is still snoozed", OccurrenceState.SNOOZED, state("a"))
        assertEquals(
            "the next rung is still the repeat",
            EscalationRung(a.scheduledInstant + 5.minutes, Channel.RING_REPEAT),
            f.arming.coordinator
                .expected()
                ?.rung,
        )
        assertFalse(
            f.arming.coordinator
                .expected()
                ?.snoozeWake ?: true,
        )
        // It is overdue, so it is armed for now, and it fires.
        val repeat = f.fire(a, now = end, rung = a.scheduledInstant + 5.minutes) as FireOutcome.Fired
        assertEquals(Channel.RING_REPEAT, repeat.rung.rung.channel)
        assertEquals("the ladder's second rung", 2, f.arming.count(EventType.ALARM_FIRED, "a"))
        assertEquals("the snooze's end is not counted twice", 1, f.arming.count(EventType.SNOOZE_ENDED, "a"))
    }

    // A rung that came due during the snooze joins the ring the snooze's end started, by the continuation rule
    // (ADR 0062): one ringer start, one session.
    @Test
    fun `a rung due during the snooze continues the ring the snooze end started`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        assertEquals(1, f.ringer.starts.size)
        f.arming.clock.now = a.scheduledInstant + 1.minutes
        controller.act("a", OccurrenceAction.SNOOZE)
        assertFalse("acting took it out of the session", sessions.isActive)
        val end = a.scheduledInstant + 11.minutes

        f.fire(a, now = end, rung = end)
        assertEquals("the snooze's end rings again", 2, f.ringer.starts.size)
        assertTrue(sessions.isRinging("a"))
        val repeat = f.fire(a, now = end, rung = a.scheduledInstant + 5.minutes) as FireOutcome.Fired

        assertTrue("it continues the ring in progress", repeat.rung.continuing)
        assertEquals("no second ringer start", 2, f.ringer.starts.size)
        assertEquals(1, sessions.items().size)
    }

    // The snooze's end is delivered like a ring: the screen is listed again, with her words.
    @Test
    fun `the snooze end rings again with the occurrence listed`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        controller.act("a", OccurrenceAction.SNOOZE)
        val end = a.scheduledInstant + 10.minutes

        f.fire(a, now = end, rung = end)

        assertEquals(listOf("Iron tablet"), sessions.items().map { it.title })
        assertEquals(
            "the telemetry row of the snooze's end",
            "RING",
            f.arming.eventsOf("a", EventType.SNOOZE_ENDED).single().let {
                f.telemetry.findForEvent(it.id)?.deliveryPath
            },
        )
    }

    // The ladder is unchanged by a snooze: a rung that has fired is never offered again, and the count is the
    // record. After snooze, wake and the repeat, nothing of this device's ladder remains.
    @Test
    fun `after the last rung nothing is armed`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        controller.act("a", OccurrenceAction.SNOOZE)
        val end = a.scheduledInstant + 10.minutes
        f.fire(a, now = end, rung = end)
        f.fire(a, now = end, rung = a.scheduledInstant + 10.minutes)

        assertEquals(2, f.arming.count(EventType.ALARM_FIRED, "a"))
        assertEquals(0, f.arming.alarms().size)
        assertEquals(null, f.arming.coordinator.expected())
    }

    // --- grace still applies while snoozed

    @Test
    fun `grace applies while snoozed and the snooze end finds a missed occurrence`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        // Snooze at 1h55: it would end at 2h05, past the CRITICAL grace of 2h.
        f.arming.clock.now = a.scheduledInstant + 1.hours + 55.minutes
        controller.act("a", OccurrenceAction.SNOOZE)
        assertEquals(OccurrenceState.SNOOZED, state("a"))
        val end = a.scheduledInstant + 2.hours + 5.minutes

        val outcome = f.fire(a, now = end, rung = end)

        assertEquals("the snooze's end finds the occurrence terminal", FireOutcome.Terminal, outcome)
        assertEquals(OccurrenceState.MISSED, state("a"))
        val missed = f.arming.eventsOf("a", EventType.MISSED).single()
        assertEquals("derived as usual, at the grace expiry", a.scheduledInstant + 2.hours, missed.effectiveAt)
        assertEquals("nothing rang for it", 1, f.ringer.starts.size)
        assertEquals(
            "no SNOOZE_ENDED: it did not fire for a terminal occurrence",
            0,
            f.arming.count(EventType.SNOOZE_ENDED, "a"),
        )
        assertEquals(0, f.arming.alarms().size)
    }

    @Test
    fun `the watchdog derives MISSED for a snoozed occurrence past its grace`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        controller.act("a", OccurrenceAction.SNOOZE)
        f.arming.clock.now = a.scheduledInstant + 3.hours

        f.arming.watchdog.run()

        assertEquals(OccurrenceState.MISSED, state("a"))
        assertNotNull(f.arming.eventsOf("a", EventType.MISSED).singleOrNull())
    }

    // --- a snooze ends once

    @Test
    fun `a second fire of the same snooze end is absorbed`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        controller.act("a", OccurrenceAction.SNOOZE)
        val end = a.scheduledInstant + 10.minutes
        f.fire(a, now = end, rung = end)
        val log = f.arming.eventLog("a")

        // The repeat rung is also at t0+10m, so the same instant now names the rung, which fires once; a further
        // fire for the snooze's own end names nothing that is expected.
        val outcome = f.fire(a, now = end, rung = end + 1.minutes)

        assertEquals(FireOutcome.NotExpected, outcome)
        assertEquals(
            log.filter { it.first != EventType.ALARM_SCHEDULED },
            f.arming.eventLog("a").filter {
                it.first !=
                    EventType.ALARM_SCHEDULED
            },
        )
    }
}
