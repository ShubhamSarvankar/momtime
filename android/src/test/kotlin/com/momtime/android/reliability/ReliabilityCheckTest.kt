package com.momtime.android.reliability

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import com.momtime.android.arming.AlarmApi
import com.momtime.android.arming.AlarmIntents
import com.momtime.android.arming.AlarmReceiver
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.MutableClock
import com.momtime.android.arming.t0
import com.momtime.android.delivery.finished
import com.momtime.android.delivery.withPendingResult
import com.momtime.android.store.CheckOutcome
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The check she starts (ADR 0069). It is the one permitted second alarm, so most of what is tested here is that it
 * stays out of the reminders' way: its identity is its own, arming it changes nothing a reminder owns, and nothing the
 * reminders do cancels it. Then that its result has one writer and one record: a fire, a timeout, and the process dying
 * mid check. What this shows is the platform's alarm calls as Robolectric shadows them. It is not a device run
 * (`MANUAL_CHECKS.md` P2-31).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class ReliabilityCheckTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val runner: CanaryRunner get() = fixture.graph.get()
    private val due = t0 + CheckPolicy.DELAY

    @After
    fun tearDown() {
        CheckEntryPoint.provider = null
        fixture.close()
    }

    /** Every `CANARY_RESULT` the check wrote. The fixture's ids are `gen-0`, `gen-1`, and so on. */
    private fun canaryEvents(): List<Event> =
        (0..40).mapNotNull { fixture.events.findById("gen-$it") }.filter { it.eventType == EventType.CANARY_RESULT }

    @Test
    fun `starting the check arms one alarm of its own, due in sixty seconds, with request code zero`() {
        val result = runner.start()

        val started = result as StartResult.Started
        val alarm = fixture.checkAlarms().single()
        assertEquals(due.toEpochMilliseconds(), alarm.triggerAtMs)
        assertNotNull("armed through setAlarmClock, as a reminder is", alarm.alarmClockInfo)
        assertEquals(CheckIntents.REQUEST_CODE, fixture.requestCode(alarm))
        assertEquals(0, CheckIntents.REQUEST_CODE)
        assertEquals(due, started.check.scheduledAt)
        assertEquals(CheckOutcome.PENDING, started.check.outcome)
        assertEquals(fixture.resolver.current.capability, started.check.resolvedTier)
        assertEquals("starting it writes no event", emptyList<Event>(), canaryEvents())
    }

    @Test
    fun `arming the check leaves the reminder's alarm, its armed record and its events as they were`() {
        fixture.seed("a", Criticality.STANDARD, t0 + 1.hours, slot = 31)
        fixture.coordinator.ensureArmed()
        val shapesBefore = fixture.shapes()
        val armedBefore = fixture.armed.current()
        val logBefore = fixture.eventLog("a")
        val operationBefore = fixture.operation(fixture.alarms().single())

        runner.start()

        assertEquals("the reminder's alarm is the same one", shapesBefore, fixture.shapes())
        assertEquals(1, fixture.alarms().size)
        assertEquals("and the check's is a second, of its own", 1, fixture.checkAlarms().size)
        assertEquals("the armed record is untouched", armedBefore, fixture.armed.current())
        assertEquals("no reminder event was written", logBefore, fixture.eventLog("a"))
        assertTrue(
            "the reminder's PendingIntent still exists",
            AlarmIntents.existing(context, 31) != null && AlarmIntents.existing(context, 31) == operationBefore,
        )
    }

    // A PendingIntent is told apart by its action, its component and its request code. The check's are its own in
    // every one, so that nothing armed, replaced or cancelled for a reminder can name it.
    @Test
    fun `the check's identity is its own in action, component and request code`() {
        val reminder = AlarmIntents.fire(context, slot = 31, rung = t0)
        val check = CheckIntents.fire(context, checkId = 1)
        val reminderIntent = shadowOf(reminder).savedIntent
        val checkIntent = shadowOf(check).savedIntent

        assertNotEquals(reminderIntent.action, checkIntent.action)
        assertEquals(ComponentName(context, AlarmReceiver::class.java), reminderIntent.component)
        assertEquals(ComponentName(context, CheckReceiver::class.java), checkIntent.component)
        assertFalse("the two intents are not the same PendingIntent", reminderIntent.filterEquals(checkIntent))
        assertNotEquals(shadowOf(reminder).requestCode, shadowOf(check).requestCode)
        assertTrue(shadowOf(check).isImmutable)
    }

    @Test
    fun `the alarm slot counter never yields the check's request code`() {
        val slots = List(50) { fixture.occurrences.allocateNextAlarmSlot() }

        assertFalse("a slot of ${CheckIntents.REQUEST_CODE} would be the check's", CheckIntents.REQUEST_CODE in slots)
        assertEquals((1..50).toList(), slots)
    }

    @Test
    fun `ensureArmed never cancels the check, whether the head moves or nothing is pending`() {
        fixture.seed("a", Criticality.STANDARD, t0 + 1.hours, slot = 31)
        fixture.seed("b", Criticality.STANDARD, t0 + 3.hours, slot = 32)
        fixture.coordinator.ensureArmed()
        runner.start()
        assertEquals(1, fixture.checkAlarms().size)

        // The head moves to b: the alarm armed for a is cancelled by its slot.
        fixture.complete("a")
        fixture.coordinator.ensureArmed()
        assertEquals("the reminder moved on", listOf(32), fixture.shapes().map { it.requestCode })
        assertEquals("the check's alarm is still there", 1, fixture.checkAlarms().size)
        assertNotNull(CheckIntents.existing(context))

        // Nothing is pending: the reminder's alarm is cancelled and its record cleared.
        fixture.complete("b")
        fixture.coordinator.ensureArmed()
        assertEquals("no reminder alarm is left", emptyList<Any>(), fixture.shapes())
        assertEquals("the check's alarm is still there", 1, fixture.checkAlarms().size)
        assertNotNull(CheckIntents.existing(context))
        assertEquals("and the check is still pending", CheckOutcome.PENDING, runner.pending()?.outcome)
    }

    @Test
    fun `when the check's alarm fires it is recorded once, with the instant it fired`() {
        val check = (runner.start() as StartResult.Started).check
        fixture.clock.now = due + 7.seconds

        runner.onFired(check.id)

        val events = canaryEvents()
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(EventType.CANARY_RESULT, event.eventType)
        assertNull("it belongs to no occurrence", event.occurrenceId)
        assertEquals(EventSource.SYSTEM, event.source)
        assertEquals(EventPayload.Canary(due, due + 7.seconds), event.payload)
        assertEquals(due + 7.seconds, event.deviceTimestamp)
        val settled = runner.recent(5).single()
        assertEquals(CheckOutcome.FIRED, settled.outcome)
        assertEquals(due + 7.seconds, settled.firedAt)
        assertNull("the check ended, so its alarm is cancelled", CheckIntents.existing(context))
        assertEquals(0, fixture.checkAlarms().size)

        runner.onFired(check.id)
        assertEquals("a second fire of the same check records nothing", 1, canaryEvents().size)
    }

    @Test
    fun `the broadcast the alarm sends reaches the runner through the receiver and finishes`() {
        val check = (runner.start() as StartResult.Started).check
        fixture.clock.now = due
        CheckEntryPoint.provider = { runner }
        val intent = shadowOf(fixture.operation(fixture.checkAlarms().single())).savedIntent
        assertEquals(check.id, intent.getLongExtra(CheckIntents.EXTRA_CHECK_ID, -1))

        val receiver = CheckReceiver()
        val pending = withPendingResult(receiver)
        shadowOf(receiver).onReceive(context, intent, AtomicBoolean())

        assertTrue("the receiver must call goAsync()", shadowOf(receiver).wentAsync())
        assertTrue("finish() was not called", finished(pending))
        assertEquals(CheckOutcome.FIRED, runner.recent(1).single().outcome)
        assertEquals(1, canaryEvents().size)
    }

    @Test
    fun `the receiver finishes when the runner fails`() {
        runner.start()
        CheckEntryPoint.provider = { error("no graph") }
        val intent = shadowOf(fixture.operation(fixture.checkAlarms().single())).savedIntent

        val receiver = CheckReceiver()
        val pending = withPendingResult(receiver)
        shadowOf(receiver).onReceive(context, intent, AtomicBoolean())

        assertTrue("finish() was not called after a failure", finished(pending))
        assertEquals(emptyList<Event>(), canaryEvents())
    }

    @Test
    fun `a check that does not fire in time records a result with no actual instant, at the timeout`() {
        val check = (runner.start() as StartResult.Started).check

        fixture.clock.now = due + CheckPolicy.TIMEOUT - 1.milliseconds
        assertNull("not yet", runner.settleOverdue())
        assertEquals(emptyList<Event>(), canaryEvents())
        assertNotNull("still waiting, so still armed", CheckIntents.existing(context))

        fixture.clock.now = due + CheckPolicy.TIMEOUT
        assertEquals(CheckOutcome.MISSED, runner.settleOverdue())

        val event = canaryEvents().single()
        assertEquals(EventPayload.Canary(due, null), event.payload)
        assertEquals(fixture.clock.now, event.deviceTimestamp)
        assertEquals(CheckOutcome.MISSED, runner.recent(1).single().outcome)
        assertNull("the check ended, so its alarm is cancelled", CheckIntents.existing(context))
        assertEquals(check.id, runner.recent(1).single().id)
        assertNull("once is enough", runner.settleOverdue())
        assertEquals(1, canaryEvents().size)
    }

    @Test
    fun `a fire after the check timed out is ignored`() {
        val check = (runner.start() as StartResult.Started).check
        fixture.clock.now = due + CheckPolicy.TIMEOUT
        runner.settleOverdue()

        runner.onFired(check.id)

        assertEquals(
            "one result only, the missed one",
            listOf(EventPayload.Canary(due, null)),
            canaryEvents().map {
                it.payload
            },
        )
        assertEquals(CheckOutcome.MISSED, runner.recent(1).single().outcome)
    }

    @Test
    fun `a fire for some other check is ignored`() {
        val check = (runner.start() as StartResult.Started).check
        fixture.clock.now = due

        runner.onFired(check.id + 100)

        assertEquals(emptyList<Event>(), canaryEvents())
        assertEquals(CheckOutcome.PENDING, runner.pending()?.outcome)
    }

    // The process died in the middle of a check. Nothing was written for it; the next time the screen opens the
    // runner settles it, and writes the missed result then (ADR 0069).
    @Test
    fun `a check the process died in is settled as missed on the next open`() {
        val name = "died-${System.nanoTime()}.db"
        val storeName = "died-store-${System.nanoTime()}.db"
        val first = ArmingFixture(context, name = name, storeName = storeName)
        val check = (first.graph.get<CanaryRunner>().start() as StartResult.Started).check
        first.close()

        val second =
            ArmingFixture(
                context,
                clock = MutableClock(due + CheckPolicy.TIMEOUT + 1.minutes),
                name = name,
                storeName = storeName,
                seedPregnancy = false,
            )
        try {
            val reopened = second.graph.get<CanaryRunner>()
            assertEquals("the check is still there", check.id, reopened.pending()?.id)

            assertEquals(CheckOutcome.MISSED, reopened.settleOverdue())

            val ids =
                (0..40).mapNotNull { second.events.findById("gen-$it") }.filter {
                    it.eventType ==
                        EventType.CANARY_RESULT
                }
            assertEquals(listOf(EventPayload.Canary(due, null)), ids.map { it.payload })
            assertNull(reopened.pending())
            assertNull(CheckIntents.existing(context))
        } finally {
            second.close()
        }
    }

    @Test
    fun `only one check runs at a time, and a second request arms nothing`() {
        runner.start()

        val second = runner.start()

        assertEquals(StartResult.AlreadyRunning, second)
        assertEquals(1, fixture.checkAlarms().size)
        assertEquals(1, runner.recent(5).size)
    }

    @Test
    fun `a check that timed out can be followed by another`() {
        runner.start()
        fixture.clock.now = due + CheckPolicy.TIMEOUT

        val next = runner.start()

        assertTrue("the old one was settled and a new one began", next is StartResult.Started)
        assertEquals(listOf(CheckOutcome.PENDING, CheckOutcome.MISSED), runner.recent(5).map { it.outcome })
        assertEquals(1, canaryEvents().size)
    }

    @Test
    fun `a reminder due within two minutes of the check defers it, at and inside the boundary`() {
        fixture.seed("a", Criticality.STANDARD, due + CheckPolicy.NEAR_REMINDER, slot = 31)
        fixture.coordinator.ensureArmed()

        assertEquals("exactly two minutes after is near", StartResult.ReminderNear, runner.start())
        assertEquals(emptyList<Any>(), fixture.checkAlarms())
        assertNull("nothing was left pending", runner.pending())

        fixture.clock.now = t0 - 1.milliseconds
        assertTrue("one millisecond further from the reminder is not near", runner.start() is StartResult.Started)
    }

    @Test
    fun `a reminder two minutes before the check's due time also defers it`() {
        fixture.seed("a", Criticality.STANDARD, due - CheckPolicy.NEAR_REMINDER, slot = 31)
        fixture.coordinator.ensureArmed()

        assertEquals(StartResult.ReminderNear, runner.start())
    }

    @Test
    fun `a reminder further than two minutes away does not defer it`() {
        fixture.seed("a", Criticality.STANDARD, due + CheckPolicy.NEAR_REMINDER + 1.milliseconds, slot = 31)
        fixture.coordinator.ensureArmed()

        assertTrue(runner.start() is StartResult.Started)
        assertEquals(1, fixture.alarms().size)
        assertEquals(1, fixture.checkAlarms().size)
    }

    @Test
    fun `when the platform refuses exact alarms the check is armed inexactly and records the tier it really had`() {
        fixture.api.refuseExact = true

        val started = runner.start() as StartResult.Started

        val alarm = fixture.checkAlarms().single()
        assertTrue("armed through setAndAllowWhileIdle", alarm.isAllowWhileIdle)
        assertNull(alarm.alarmClockInfo)
        assertEquals(DeliveryCapability.TIER_1, runner.recent(1).single().resolvedTier)
        assertEquals(due, started.check.scheduledAt)
    }

    @Test
    fun `a check whose alarm cannot be armed never ran, and leaves nothing pending and no result`() {
        val failing =
            object : AlarmApi {
                override fun setAlarmClock(
                    triggerAtMillis: Long,
                    show: PendingIntent?,
                    operation: PendingIntent,
                ) = error("the platform failed")

                override fun setAndAllowWhileIdle(
                    triggerAtMillis: Long,
                    operation: PendingIntent,
                ) = error("the platform failed")

                override fun cancel(operation: PendingIntent) = Unit
            }
        val broken =
            CanaryRunner(
                context,
                fixture.graph.get(),
                fixture.events,
                failing,
                fixture.resolver,
                fixture.armed,
                fixture.clock,
                { "x" },
            )

        assertEquals(StartResult.Failed, broken.start())

        assertNull(broken.pending())
        assertEquals("it is no part of the history", emptyList<Any>(), broken.recent(5))
        assertEquals(emptyList<Any>(), fixture.checkAlarms())
        assertNull(fixture.events.findById("x"))
    }

    @Test
    fun `the check never changes a reminder's events, state or alarm across its whole life`() {
        fixture.seed("a", Criticality.STANDARD, t0 + 4.hours, slot = 31)
        fixture.coordinator.ensureArmed()
        val logBefore = fixture.eventLog("a")
        val shapesBefore = fixture.shapes()
        val stateBefore = fixture.occurrences.findById("a")

        val check = (runner.start() as StartResult.Started).check
        fixture.clock.now = due + 3.seconds
        runner.onFired(check.id)
        runner.start()
        fixture.clock.now = fixture.clock.now + 1.hours
        runner.settleOverdue()

        assertEquals(logBefore, fixture.eventLog("a"))
        assertEquals(shapesBefore, fixture.shapes())
        assertEquals(stateBefore, fixture.occurrences.findById("a"))
        assertEquals(2, canaryEvents().size)
    }

    @Test
    fun `neither the check's receiver nor its screen is exported`() {
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, CheckReceiver::class.java), 0)
        val activity =
            context.packageManager.getActivityInfo(ComponentName(context, ReliabilityCheckActivity::class.java), 0)

        assertFalse("the check receiver must not be exported", receiver.exported)
        assertFalse("the check screen must not be exported", activity.exported)
    }
}
