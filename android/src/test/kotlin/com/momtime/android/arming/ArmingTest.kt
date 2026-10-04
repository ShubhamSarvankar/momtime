package com.momtime.android.arming

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.momtime.android.capability.DeliveryMechanism
import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Arming, against the shadowed `AlarmManager` as the oracle (ADR 0053) at SDK 29, 31, 33 and 36: the SDKs
 * where the permission model differs. After every arming operation the shadow is asked what the platform
 * holds, and it must hold exactly one alarm.
 *
 * What this shows is that the code arms what it says it arms, through the call it says, with the request code
 * and flags it says. It does not show that a real device fires it, on time, in Doze, with the app killed:
 * that is `MANUAL_CHECKS.md`.
 *
 * Test names are short on purpose: Robolectric names its data directory after the class and the method, and
 * a long path breaks the database on Windows.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 36])
class ArmingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)

    @After
    fun tearDown() = fixture.close()

    private fun single(): ShadowAlarmManager.ScheduledAlarm {
        assertEquals("the platform must hold exactly one alarm", 1, fixture.alarms().size)
        return fixture.alarms().single()
    }

    private val exactNeedsCapability get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @Test
    fun `trigger type and code`() {
        val a = fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours

        val result = fixture.coordinator.ensureArmed()

        assertTrue(result is EnsureResult.Armed)
        val alarm = single()
        assertEquals("trigger", t0.toEpochMilliseconds(), alarm.triggerAtMs)
        assertEquals("type", android.app.AlarmManager.RTC_WAKEUP, alarm.type)
        assertNotNull("exact capability arms through setAlarmClock", alarm.alarmClockInfo)
        assertEquals("request code is the slot", a.alarmSlot, fixture.requestCode(alarm))
        val operation = shadowOf(fixture.operation(alarm))
        assertTrue("immutable", operation.isImmutable)
        assertTrue("a broadcast", operation.isBroadcast)
        assertEquals(
            "explicit component",
            ComponentName(context, AlarmReceiver::class.java),
            operation.savedIntent.component,
        )
        assertEquals(AlarmIntents.ACTION_FIRE, operation.savedIntent.action)
        assertEquals(t0.toEpochMilliseconds(), operation.savedIntent.getLongExtra(AlarmIntents.EXTRA_RUNG_MILLIS, 0L))
        assertEquals(31, operation.savedIntent.getIntExtra(AlarmIntents.EXTRA_SLOT, -1))
        assertTrue(
            "FLAG_IMMUTABLE on the flags",
            operation.flags and PendingIntent.FLAG_IMMUTABLE != 0,
        )
    }

    @Test
    fun `inexact trigger type and code`() {
        if (!exactNeedsCapability) return // below API 31 there is no way to lose exact capability
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val a = fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours

        val result = fixture.coordinator.ensureArmed() as EnsureResult.Armed

        assertEquals(DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE, result.mechanism)
        val alarm = single()
        assertEquals(t0.toEpochMilliseconds(), alarm.triggerAtMs)
        assertEquals(android.app.AlarmManager.RTC_WAKEUP, alarm.type)
        assertNull("not an alarm clock", alarm.alarmClockInfo)
        assertTrue("allowed while idle", alarm.isAllowWhileIdle)
        assertEquals(a.alarmSlot, fixture.requestCode(alarm))
        assertTrue(shadowOf(fixture.operation(alarm)).isImmutable)
    }

    // A rung that is already due is armed for now, never in the past, and keeps its own instant.
    @Test
    fun `a due rung is armed for now`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 + 30.minutes

        fixture.coordinator.ensureArmed()

        val alarm = single()
        assertEquals(fixture.clock.now.toEpochMilliseconds(), alarm.triggerAtMs)
        assertEquals(
            "the rung keeps its own instant",
            t0.toEpochMilliseconds(),
            shadowOf(fixture.operation(alarm)).savedIntent.getLongExtra(AlarmIntents.EXTRA_RUNG_MILLIS, 0L),
        )
    }

    // Every request code is its occurrence's slot. Two ids with the same hash code still get their own codes,
    // and the schema refuses two occurrences with one slot, so a collision cannot be built.
    @Test
    fun `codes are unique per occurrence`() {
        assertEquals("the fixture ids must hash alike", "Aa".hashCode(), "BB".hashCode())
        val first = fixture.seed("Aa", Criticality.STANDARD, t0, slot = 11)
        val second = fixture.seed("BB", Criticality.STANDARD, t0 + 1.hours, slot = 12)
        val codes = mutableMapOf<String, MutableSet<Int>>()

        fun observe() {
            val code = fixture.requestCode(single())
            val owner = fixture.occurrences.findByAlarmSlot(code)
            assertNotNull("request code $code is no occurrence's slot", owner)
            codes.getOrPut(owner!!.id) { mutableSetOf() } += code
        }

        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()
        observe()
        fixture.handler.onFire(11, t0) // rung 2 of the first occurrence
        observe()
        fixture.handler.onFire(11, t0 + 10.minutes)
        observe() // the head is now the second occurrence
        fixture.clock.now = t0 + 1.hours
        fixture.handler.onFire(12, t0 + 1.hours)
        observe()

        assertEquals(mapOf("Aa" to setOf(11), "BB" to setOf(12)), codes)
        assertEquals(setOf(first.alarmSlot, second.alarmSlot).size, 2)
        val attempt = runCatching { fixture.seed("CC", Criticality.GENTLE, t0, slot = 11) }
        assertTrue("two occurrences must not share a slot", attempt.isFailure)
    }

    @Test
    fun `rearm on fire is the next rung`() {
        val a = fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()
        assertEquals(t0.toEpochMilliseconds(), single().triggerAtMs)

        val outcome = fixture.handler.onFire(31, t0)

        assertTrue(outcome is FireOutcome.Fired)
        val next = single()
        assertEquals("the next rung", (t0 + 10.minutes).toEpochMilliseconds(), next.triggerAtMs)
        assertEquals("the same code, replaced in place", a.alarmSlot, fixture.requestCode(next))
        assertEquals(1, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
        assertEquals(listOf(Channel.RING), fixture.delivery.delivered.map { it.rung.channel })

        fixture.clock.now = t0 + 10.minutes
        fixture.handler.onFire(31, t0 + 10.minutes)

        assertEquals("the ladder is spent, so nothing is armed", 0, fixture.alarms().size)
        assertNull("and the record is cleared", fixture.armed.current())
        assertEquals(
            listOf(Channel.RING, Channel.RING_REPEAT),
            fixture.delivery.delivered.map { it.rung.channel },
        )
    }

    // Request codes are per occurrence, so arming another occurrence's rung does not replace the alarm
    // already armed: the previous one is cancelled, from the armed record.
    @Test
    fun `the head moves and one alarm remains`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 1)
        val b = fixture.seed("b", Criticality.STANDARD, t0 + 2.hours, slot = 2)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        assertEquals(1, fixture.requestCode(single()))

        fixture.complete("a")
        fixture.coordinator.ensureArmed()

        val alarm = single()
        assertEquals("the second occurrence is the head", b.alarmSlot, fixture.requestCode(alarm))
        assertEquals((t0 + 2.hours).toEpochMilliseconds(), alarm.triggerAtMs)
        assertEquals(2, fixture.armed.current()?.alarmSlot)
    }

    @Test
    fun `nothing pending cancels the armed alarm`() {
        fixture.seed("a", Criticality.GENTLE, t0, slot = 1)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        assertEquals(1, fixture.alarms().size)

        fixture.complete("a")
        val result = fixture.coordinator.ensureArmed()

        assertEquals(EnsureResult.NothingPending, result)
        assertEquals(0, fixture.alarms().size)
        assertNull(fixture.armed.current())
    }

    // The caregiver rung of A (ten minutes in) comes before the ring rung of B (twelve minutes in). The device
    // does not deliver it, so it must not be the rung that is armed.
    @Test
    fun `a caregiver rung never delays a ring`() {
        fixture.seed("a", Criticality.CRITICAL, t0, slot = 1)
        val b = fixture.seed("b", Criticality.STANDARD, t0 + 12.minutes, slot = 2)
        fixture.recordFired("a", 2)
        fixture.clock.now = t0 + 3.minutes

        val result = fixture.coordinator.ensureArmed() as EnsureResult.Armed

        assertEquals("b", result.selection.occurrenceId)
        val alarm = single()
        assertEquals(b.alarmSlot, fixture.requestCode(alarm))
        assertEquals((t0 + 12.minutes).toEpochMilliseconds(), alarm.triggerAtMs)
    }

    // Selection reads the record of fired rungs. With the clock set back to before the ladder, a rung that
    // has fired must not come back (golden scenario 14, whose full test is PR 6).
    @Test
    fun `a fired rung stays fired when the clock goes back`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 1)
        fixture.recordFired("a", 1)
        fixture.clock.now = t0 - 3.hours

        val result = fixture.coordinator.ensureArmed()

        assertTrue("the fired rung came back or nothing was armed: $result", result is EnsureResult.Armed)
        assertEquals(t0 + 10.minutes, (result as EnsureResult.Armed).selection.rung.instant)
        assertEquals((t0 + 10.minutes).toEpochMilliseconds(), single().triggerAtMs)
    }

    // The ring rungs of two occurrences at the same instant: one is armed, and when it has fired the other is
    // armed at once, at an instant that has already passed. Which one is first never depends on the order the
    // occurrences were read in.
    @Test
    fun `a tie is broken by id and the second is armed at once`() {
        // Inserted in the opposite order to their ids.
        val b = fixture.seed("b", Criticality.GENTLE, t0, slot = 6)
        val a = fixture.seed("a", Criticality.GENTLE, t0, slot = 5)
        fixture.clock.now = t0 - 1.minutes

        fixture.coordinator.ensureArmed()
        assertEquals("a comes first whatever the read order", a.alarmSlot, fixture.requestCode(single()))

        fixture.clock.now = t0
        fixture.handler.onFire(a.alarmSlot, t0)

        val alarm = single()
        assertEquals("the second is armed immediately", b.alarmSlot, fixture.requestCode(alarm))
        assertEquals(t0.toEpochMilliseconds(), alarm.triggerAtMs)
    }

    // ALARM_SCHEDULED is for a rung armed for the first time or an expected rung that changed. A refresh
    // re-arms the same rung and writes nothing (decision 6).
    @Test
    fun `scheduled is written once per rung`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 1)
        fixture.clock.now = t0 - 1.hours

        val first = fixture.coordinator.ensureArmed() as EnsureResult.Armed
        val second = fixture.coordinator.ensureArmed() as EnsureResult.Armed
        val third = fixture.coordinator.ensureArmed() as EnsureResult.Armed

        assertTrue(first.scheduledWritten)
        assertFalse(second.scheduledWritten)
        assertFalse(third.scheduledWritten)
        assertEquals(1, fixture.eventsOf("a", EventType.ALARM_SCHEDULED).size)
        single()

        fixture.clock.now = t0
        fixture.handler.onFire(1, t0)

        assertEquals("the expected rung changed", 2, fixture.eventsOf("a", EventType.ALARM_SCHEDULED).size)
        assertEquals(1, fixture.alarms().size)
    }

    @Test
    fun `the armed record says what was armed`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 9)
        fixture.clock.now = t0 - 1.hours

        fixture.coordinator.ensureArmed()

        val record = checkNotNull(fixture.armed.current())
        assertEquals(9, record.alarmSlot)
        assertEquals(t0, record.rungInstant)
        assertEquals(fixture.clock.now, record.armedAt)
        assertEquals(7L, record.bootCount)
        assertTrue(record.exactAllowed)
    }
}
