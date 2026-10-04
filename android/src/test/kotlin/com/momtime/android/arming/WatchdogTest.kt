package com.momtime.android.arming

import android.content.Context
import android.os.Build
import com.momtime.android.capability.DeliveryMechanism
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.OccurrenceState
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowPausedSystemClock
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The watchdog pass (ADR 0058) against the one entry point and the shadowed `AlarmManager` as the oracle, at
 * SDK 29, 31, 33 and 36. A lost alarm is produced the way the system produces it (a cancelled `PendingIntent`,
 * a missing record), never by editing the shadow's list.
 *
 * Each evidence signal has a test in which it is the only thing that differs from a correct state, and the
 * test compares the evidence exactly, so a signal that stops working fails its own test.
 *
 * It shows what the code does against a model of the platform. It does not show how a real device's Doze
 * cadence delays the job (`MANUAL_CHECKS.md` P2-13).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 36])
class WatchdogTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val canLoseExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @After
    fun tearDown() = fixture.close()

    /** One CRITICAL occurrence due at [t0], armed an hour ahead. The state every test starts from. */
    private fun armedAndCorrect() {
        fixture.seed("a", Criticality.CRITICAL, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        assertEquals("the platform must hold exactly one alarm", 1, fixture.alarms().size)
    }

    private fun repairs() = fixture.count(EventType.WATCHDOG_REPAIR, "a")

    private fun deltaSince(before: List<Pair<EventType, String>>) =
        fixture.eventDelta("a", before = before).map { it.first }.sortedBy { it.name }

    // Golden scenario 17: a pass over a correct state writes no event, changes no state, and leaves the armed
    // alarm unchanged in type, trigger time and request code.
    @Test
    fun `a pass over a correct state changes nothing`() {
        armedAndCorrect()
        val shapesBefore = fixture.shapes()
        val logBefore = fixture.eventLog("a")

        repeat(3) {
            val result = fixture.watchdog.run()
            assertEquals(emptySet<RepairEvidence>(), result.evidence)
            assertEquals(0, result.missed)
        }

        assertEquals("no WATCHDOG_REPAIR", 0, repairs())
        assertEquals("no event of any kind", logBefore, fixture.eventLog("a"))
        assertEquals(OccurrenceState.PENDING, fixture.occurrences.findById("a")?.state)
        assertEquals("type, trigger time and request code", shapesBefore, fixture.shapes())
    }

    // Plan item "watchdog detects a manually cleared alarm and repairs it".
    @Test
    fun `a cleared alarm is repaired`() {
        armedAndCorrect()
        val shapesBefore = fixture.shapes()
        val logBefore = fixture.eventLog("a")
        fixture.loseAlarm(31)
        assertEquals("the alarm is gone", 0, fixture.alarms().size)

        val result = fixture.watchdog.run()

        assertEquals(setOf(RepairEvidence.ALARM_ABSENT), result.evidence)
        assertEquals(1, repairs())
        assertEquals("only the repair was written", listOf(EventType.WATCHDOG_REPAIR), deltaSince(logBefore))
        assertEquals("the same alarm is armed again", shapesBefore, fixture.shapes())
        assertEquals(OccurrenceState.PENDING, fixture.occurrences.findById("a")?.state)
    }

    @Test
    fun `a missing record is repaired`() {
        armedAndCorrect()
        val logBefore = fixture.eventLog("a")
        fixture.armed.clear()

        val result = fixture.watchdog.run()

        assertEquals(setOf(RepairEvidence.RECORD_MISSING), result.evidence)
        // A lost record writes one extra ALARM_SCHEDULED, because "first armed" is judged by the record (ADR 0053).
        assertEquals(
            listOf(EventType.ALARM_SCHEDULED, EventType.WATCHDOG_REPAIR),
            deltaSince(logBefore),
        )
        assertNotNull("the record is back", fixture.armed.current())
        assertEquals(1, fixture.alarms().size)
    }

    @Test
    fun `a record for another rung is repaired`() {
        armedAndCorrect()
        val record = checkNotNull(fixture.armed.current())
        fixture.armed.replace(record.copy(rungInstant = record.rungInstant + 5.minutes))

        val result = fixture.watchdog.run()

        assertEquals(setOf(RepairEvidence.RECORD_MISMATCH), result.evidence)
        assertEquals(1, repairs())
        assertEquals(record.rungInstant, fixture.armed.current()?.rungInstant)
    }

    @Test
    fun `a changed boot count is repaired and the next pass is clean`() {
        armedAndCorrect()
        fixture.bootCount = 8

        val result = fixture.watchdog.run()

        assertEquals(setOf(RepairEvidence.BOOT_COUNT_CHANGED), result.evidence)
        assertEquals(1, repairs())
        assertEquals("the record is rewritten with the new boot count", 8L, fixture.armed.current()?.bootCount)
        assertEquals(emptySet<RepairEvidence>(), fixture.watchdog.run().evidence)
        assertEquals("and the repair is not written twice", 1, repairs())
    }

    // Exact capability cannot be lost below API 31, so SDK 29 asserts that and the others assert the repair.
    @Test
    fun `a changed exact capability is repaired`() {
        armedAndCorrect()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = fixture.watchdog.run()

        if (canLoseExact) {
            assertEquals(setOf(RepairEvidence.EXACT_CAPABILITY_CHANGED), result.evidence)
            assertEquals(1, repairs())
            val alarm = fixture.alarms().single()
            assertNull("armed inexactly now", alarm.alarmClockInfo)
            assertEquals(fixture.resolver.current.mechanism, DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE)
        } else {
            assertEquals("capability cannot be lost below API 31", emptySet<RepairEvidence>(), result.evidence)
            assertEquals(0, repairs())
        }
    }

    @Test
    fun `an update after arming is repaired`() {
        armedAndCorrect()
        // The update lands ten minutes after the alarm was armed, and the watchdog runs five minutes after that.
        fixture.clock.now = fixture.clock.now + 10.minutes
        fixture.appUpdatedAt = fixture.clock.now
        fixture.clock.now = fixture.clock.now + 5.minutes

        val result = fixture.watchdog.run()

        assertEquals(setOf(RepairEvidence.APP_UPDATED), result.evidence)
        assertEquals(1, repairs())
        assertEquals(
            "re armed after the update, so the next pass is clean",
            emptySet<RepairEvidence>(),
            fixture.watchdog.run().evidence,
        )
    }

    @Test
    fun `an exact rung that is overdue is repaired`() {
        armedAndCorrect()
        fixture.clock.now = t0 + WatchdogEvidence.EXACT_TOLERANCE
        assertEquals("at the tolerance, not yet", emptySet<RepairEvidence>(), fixture.watchdog.run().evidence)

        fixture.clock.now = t0 + WatchdogEvidence.EXACT_TOLERANCE + 1.milliseconds
        val result = fixture.watchdog.run()

        assertEquals(setOf(RepairEvidence.RUNG_OVERDUE), result.evidence)
        assertEquals(1, repairs())
        assertEquals("armed for now", fixture.clock.now.toEpochMilliseconds(), fixture.alarms().single().triggerAtMs)
    }

    // An inexact alarm may be legitimately late. Its tolerance is longer, and a late one is not repaired.
    @Test
    fun `an inexact rung is given longer`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        armedAndCorrect()
        fixture.clock.now = t0 + 10.minutes
        val result = fixture.watchdog.run()

        if (canLoseExact) {
            assertEquals(
                "ten minutes late is within the inexact tolerance",
                emptySet<RepairEvidence>(),
                result.evidence,
            )
            assertEquals(0, repairs())
            fixture.clock.now = t0 + WatchdogEvidence.INEXACT_TOLERANCE + 1.milliseconds
            assertEquals(setOf(RepairEvidence.RUNG_OVERDUE), fixture.watchdog.run().evidence)
            assertEquals(1, repairs())
        } else {
            assertEquals(
                "exact below API 31, so two minutes is the tolerance",
                setOf(RepairEvidence.RUNG_OVERDUE),
                result.evidence,
            )
        }
    }

    // Reconcile is dispatched by every pass: an occurrence whose grace expired while the app was closed is
    // derived to MISSED even though no alarm will ever fire for it.
    @Test
    fun `reconcile is dispatched and derives MISSED`() {
        fixture.seed("b", Criticality.STANDARD, t0, slot = 32)
        fixture.clock.now = t0 + 5.hours

        val result = fixture.watchdog.run()

        assertEquals(1, result.missed)
        assertEquals(OccurrenceState.MISSED, fixture.occurrences.findById("b")?.state)
        val missed = fixture.eventsOf("b", EventType.MISSED).single()
        assertEquals("the grace expiry, not the time the pass ran", t0 + 4.hours, missed.effectiveAt)
        assertEquals(0, fixture.alarms().size)
        assertEquals("nothing was rung", emptyList<FiredRung>(), fixture.delivery.delivered)
        assertEquals("nothing was lost either", 0, fixture.count(EventType.WATCHDOG_REPAIR, "b"))
    }

    // The catch up rule through the watchdog (ADR 0056). CRITICAL rungs on a device: t0 and t0 + 5 minutes.
    @Test
    fun `a rung overdue within the window is armed for now and the worker does not ring it`() {
        fixture.seed("a", Criticality.CRITICAL, t0, slot = 31)
        fixture.clock.now = t0 + 20.minutes

        val result = fixture.watchdog.run()

        assertTrue(RepairEvidence.RUNG_OVERDUE in result.evidence)
        assertEquals("armed for now", fixture.clock.now.toEpochMilliseconds(), fixture.alarms().single().triggerAtMs)
        assertEquals(t0, fixture.armed.current()?.rungInstant)
        assertEquals("a worker never starts the ringer", emptyList<FiredRung>(), fixture.delivery.delivered)
        assertEquals("and writes no ALARM_FIRED", 0, fixture.count(EventType.ALARM_FIRED, "a"))
    }

    // The boundary is inclusive: the second rung is exactly 30 minutes overdue and still rings.
    @Test
    fun `a rung exactly at the boundary is armed and one millisecond later it is not`() {
        fixture.seed("a", Criticality.CRITICAL, t0, slot = 31)
        fixture.clock.now = t0 + 35.minutes

        val atBoundary = fixture.watchdog.run().ensured
        assertTrue("armed, not nothing pending: $atBoundary", atBoundary is EnsureResult.Armed)
        assertEquals(
            "the first rung is stale, the second is exactly 30 minutes late",
            t0 + 5.minutes,
            (atBoundary as EnsureResult.Armed).selection.rung.instant,
        )
        assertEquals(1, fixture.alarms().size)

        fixture.clock.now = t0 + 35.minutes + 1.milliseconds
        val past = fixture.watchdog.run().ensured
        assertEquals(EnsureResult.NothingPending, past)
        assertEquals("nothing rings late", 0, fixture.alarms().size)
        assertEquals(OccurrenceState.PENDING, fixture.occurrences.findById("a")?.state)
    }

    // Beyond the window nothing rings, and the occurrence is derived to MISSED at the end of its grace.
    @Test
    fun `a stale rung is not rung and the occurrence ends MISSED`() {
        fixture.seed("a", Criticality.CRITICAL, t0, slot = 31)
        fixture.clock.now = t0 + 90.minutes

        val stale = fixture.watchdog.run()
        assertEquals(EnsureResult.NothingPending, stale.ensured)
        assertEquals(0, fixture.alarms().size)
        assertEquals(emptyList<FiredRung>(), fixture.delivery.delivered)

        fixture.clock.now = t0 + 2.hours
        val grace = fixture.watchdog.run()
        assertEquals(1, grace.missed)
        assertEquals(OccurrenceState.MISSED, fixture.occurrences.findById("a")?.state)
        assertEquals(0, fixture.alarms().size)
    }

    // A reboot after which the device has been up longer than it had been when the alarm was armed. Uptime at
    // arm was one hour, uptime now is five, so a comparison of uptimes says nothing happened. The boot count
    // says the device restarted, which is the evidence.
    @Test
    fun `a reboot after a longer uptime is still detected`() {
        ShadowPausedSystemClock.reset()
        ShadowSystemClock.advanceBy(1.hours.toJavaDuration())
        armedAndCorrect()
        val uptimeAtArm = ShadowSystemClock.nanoTime()

        // The device restarts, and runs for five hours.
        ShadowPausedSystemClock.reset()
        ShadowSystemClock.advanceBy(5.hours.toJavaDuration())
        fixture.bootCount = 8
        val uptimeNow = ShadowSystemClock.nanoTime()
        assertTrue("the case that distinguishes the two: up longer now than at arm", uptimeNow > uptimeAtArm)
        assertFalse("an uptime comparison would have seen no restart", uptimeNow < uptimeAtArm)

        val result = fixture.watchdog.run()

        assertEquals(setOf(RepairEvidence.BOOT_COUNT_CHANGED), result.evidence)
        assertEquals(1, repairs())
    }

    @Test
    fun `a reboot after a shorter uptime is detected as well`() {
        ShadowPausedSystemClock.reset()
        ShadowSystemClock.advanceBy(5.hours.toJavaDuration())
        armedAndCorrect()
        ShadowPausedSystemClock.reset()
        ShadowSystemClock.advanceBy(1.hours.toJavaDuration())
        fixture.bootCount = 8

        assertEquals(setOf(RepairEvidence.BOOT_COUNT_CHANGED), fixture.watchdog.run().evidence)
    }

    // A fire writes ALARM_FIRED and then arms the next rung. A watchdog pass that read the state between the two
    // would see a record that does not match and call a healthy chain lost, so each waits for the other. Both end
    // in ensureArmed, which is synchronized anyway, so waiting at the end proves nothing: what is observed is what
    // each has done while the other holds the coordinator, which must be nothing yet.
    @Test
    fun `the watchdog waits while a fire holds the coordinator`() {
        fixture.seed("b", Criticality.STANDARD, t0, slot = 32)
        fixture.clock.now = t0 + 5.hours

        val untouched =
            observedWhileHeld(work = { fixture.watchdog.run() }) {
                fixture.occurrences.findById("b")?.state == OccurrenceState.PENDING
            }

        assertTrue("the watchdog reconciled while the coordinator was held", untouched)
        assertEquals("and it finished once released", OccurrenceState.MISSED, fixture.occurrences.findById("b")?.state)
    }

    @Test
    fun `the fire path waits while the watchdog holds the coordinator`() {
        armedAndCorrect()
        fixture.clock.now = t0

        val untouched =
            observedWhileHeld(work = { fixture.handler.onFire(31, t0) }) {
                fixture.count(EventType.ALARM_FIRED, "a") == 0
            }

        assertTrue("the fire path wrote ALARM_FIRED while the coordinator was held", untouched)
        assertEquals("and it fired once released", 1, fixture.count(EventType.ALARM_FIRED, "a"))
    }

    /**
     * Starts [work] on another thread while this one holds the coordinator, and returns what [observe] says
     * after a moment. Releases the coordinator and waits for [work] to finish before it returns.
     */
    private fun observedWhileHeld(
        work: () -> Unit,
        observe: () -> Boolean,
    ): Boolean {
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread {
                fixture.coordinator.exclusive {
                    holding.countDown()
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        assertTrue(holding.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val finished = AtomicBoolean(false)
        val worker =
            thread {
                work()
                finished.set(true)
            }
        Thread.sleep(OBSERVE_MILLIS)
        val observed = observe()
        release.countDown()
        worker.join(WAIT_SECONDS * MILLIS)
        holder.join(WAIT_SECONDS * MILLIS)
        assertTrue("it finishes once released", finished.get())
        return observed
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val OBSERVE_MILLIS = 300L
        const val MILLIS = 1000L
    }

    @Test
    fun `nothing pending is no evidence and no repair`() {
        val result = fixture.watchdog.run()
        assertEquals(emptySet<RepairEvidence>(), result.evidence)
        assertEquals(EnsureResult.NothingPending, result.ensured)
        assertEquals(0, fixture.alarms().size)
    }
}
