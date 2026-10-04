package com.momtime.android.system

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.momtime.android.arming.AlarmIntents
import com.momtime.android.arming.FireOutcome
import com.momtime.android.arming.Presentation
import com.momtime.android.arming.t0
import com.momtime.android.delivery.DeliveryFixture
import com.momtime.android.delivery.NotificationChannels
import com.momtime.android.delivery.finished
import com.momtime.android.delivery.withPendingResult
import com.momtime.android.work.Work
import com.momtime.android.work.WorkEntryPoint
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.OccurrenceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The system broadcasts (ADR 0067): boot, an app update, a clock change and a grant of exact alarm access, each
 * delivered to the receiver as the system would, answered by unique work (run here by `WorkManager`'s test driver)
 * that dispatches `Reconcile` and calls `ensureArmed`, over the real delivery port and a real database. Golden
 * scenario 14 is the clock tests, by name.
 *
 * Nothing here shows the platform sending a broadcast, or a real alarm firing in Doze after a restart: the
 * receiver is called with the intent the system would send (`MANUAL_CHECKS.md` P2-26 to P2-29).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 34, 36])
class SystemBroadcastTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        WorkEntryPoint.provider = { f.arming.passes }
    }

    @After
    fun tearDown() {
        WorkEntryPoint.provider = null
        f.close()
    }

    /** Delivers a system broadcast with [action] to the receiver, and waits until it has enqueued its work. */
    private fun broadcast(action: String) {
        val receiver = SystemBroadcastReceiver()
        val pending = withPendingResult(receiver)
        shadowOf(receiver).onReceive(context, Intent(action), AtomicBoolean())
        assertTrue("the receiver did not finish the broadcast", finished(pending))
    }

    /** The one alarm that is armed. A test that reads it asserts first that there is exactly one. */
    private fun armed(): org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm {
        assertEquals("exactly one alarm is armed", 1, f.arming.alarms().size)
        return f.arming.alarms().single()
    }

    private fun alarmRung(): Instant {
        val intent = shadowOf(f.arming.operation(armed())).savedIntent
        return Instant.fromEpochMilliseconds(intent.getLongExtra(AlarmIntents.EXTRA_RUNG_MILLIS, 0L))
    }

    private fun alarmSlot(): Int =
        shadowOf(f.arming.operation(armed())).savedIntent.getIntExtra(AlarmIntents.EXTRA_SLOT, -1)

    private fun triggerAt(): Instant = Instant.fromEpochMilliseconds(armed().triggerAtMs)

    private fun state(id: String) =
        f.arming.occurrences
            .findById(id)
            ?.state

    private fun workInfos(event: SystemEvent): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(Work.systemName(event)).get()

    // --- boot

    @Test
    fun `boot is answered by unique one time work that ran the pass`() {
        f.due("a", Criticality.STANDARD, slot = 31)
        f.arming.clearAlarms()

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        val info = workInfos(SystemEvent.BOOT).single()
        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals("the next alarm exists again", 1, f.arming.alarms().size)
        assertEquals(31, alarmSlot())
    }

    // A restart clears every alarm. Boot dispatches Reconcile first, so an occurrence whose grace ended while the
    // phone was off is MISSED, silently, and has no rung to deliver.
    @Test
    fun `boot derives MISSED for an occurrence past its grace and rings nothing`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.arming.clearAlarms()
        f.arming.clock.now = a.scheduledInstant + 3.hours

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(OccurrenceState.MISSED, state("a"))
        val missed = f.arming.eventsOf("a", EventType.MISSED).single()
        assertEquals("derived at the grace expiry, not at boot", a.scheduledInstant + 2.hours, missed.effectiveAt)
        assertEquals("nothing is left to arm", 0, f.arming.alarms().size)
        f.assertNothingRang()
        assertNull("and nothing is shown", f.posted(31))
        assertEquals(0, f.arming.count(EventType.ALARM_FIRED, "a"))
    }

    // An overdue rung within grace is armed for now. Boot decides nothing about how it is presented and rings
    // nothing: the alarm path does that when the alarm fires (ADR 0056).
    @Test
    fun `boot arms an overdue rung for now and does not ring it`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.arming.clearAlarms()
        f.arming.clock.now = a.scheduledInstant + 20.minutes

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals("armed for now", f.arming.clock.now, triggerAt())
        assertEquals("it is the first rung, whose own instant is in the intent", a.scheduledInstant, alarmRung())
        f.assertNothingRang()
        assertEquals(0, f.arming.count(EventType.ALARM_FIRED, "a"))
        assertNull(shadowOf(context as Application).nextStartedService)
        // The alarm fires, as AlarmManager would deliver it, and the alarm path rings it.
        val outcome = f.fire(a, now = f.arming.clock.now, rung = alarmRung())
        assertTrue(outcome is FireOutcome.Fired)
        assertEquals("the alarm path started the ringer, once", 1, f.ringer.starts.size)
    }

    // A night with the phone off: both occurrences are still in grace and beyond the catch up window. Each gets its
    // own silent notice, on the Quiet notices channel, and nothing rings.
    @Test
    fun `a night with the phone off gives each occurrence its own silent notice`() {
        val a = f.due("a", Criticality.STANDARD, scheduled = t0, slot = 31)
        val b = f.due("b", Criticality.STANDARD, scheduled = t0 + 1.hours, slot = 32)
        f.arming.clearAlarms()
        f.arming.clock.now = t0 + 3.hours

        broadcast(Intent.ACTION_BOOT_COMPLETED)
        var fires = 0
        while (f.arming.alarms().isNotEmpty() && fires++ < MAX_FIRES) {
            val outcome = f.arming.handler.onFire(alarmSlot(), alarmRung()) as? FireOutcome.Fired
            assertEquals(Presentation.SILENT_NOTICE, checkNotNull(outcome).rung.presentation)
        }

        assertEquals(OccurrenceState.PENDING, state(a.id))
        for (occurrence in listOf(a, b)) {
            assertNotNull("${occurrence.id} has its notice", f.posted(occurrence.alarmSlot))
            assertEquals(NotificationChannels.QUIET, f.channelOf(occurrence.alarmSlot))
            assertEquals("SILENT_NOTICE", f.telemetryOf(occurrence.id).deliveryPath)
        }
        f.assertNothingRang()
        assertEquals(0, f.sound.starts)
    }

    // Android 15 forbids starting a mediaPlayback foreground service from a boot receiver. Boot starts no service at
    // all, foreground or otherwise, with a ringing rung overdue and everything granted.
    @Test
    @Config(sdk = [35, 36])
    fun `boot starts no service on Android 15 and later`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.arming.clearAlarms()
        f.arming.clock.now = a.scheduledInstant + 10.minutes

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertNull("no service was started", shadowOf(context as Application).nextStartedService)
        f.assertNothingRang()
        assertEquals(0, f.sound.starts)
        assertEquals(0, f.vibration.starts.size)
        assertEquals("only an alarm was armed", 1, f.arming.alarms().size)
    }

    // --- scenario 14: the clock set while a ladder is armed

    // Golden scenario 14 (backward). A rung that already fired is never fired again, and the next rung is never
    // armed in the past. The rung is overdue and was armed for now; the clock is then set back to before it, and the
    // time change re arms it where it belongs, ahead of the clock.
    @Test
    fun `scenario 14 clock set backward re arms the next rung and fires nothing again`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        assertEquals(1, f.arming.count(EventType.ALARM_FIRED, "a"))
        // Twenty minutes on, the repeat rung (five minutes in) is overdue and armed for now.
        f.arming.clock.now = a.scheduledInstant + 20.minutes
        broadcast(Intent.ACTION_TIME_CHANGED)
        assertEquals("overdue, so armed for now", f.arming.clock.now, triggerAt())
        val repeat = a.scheduledInstant + 5.minutes
        assertEquals(repeat, alarmRung())
        val log = f.arming.eventLog("a")

        // The user sets the clock back, to a minute after the first rung.
        f.arming.clock.now = a.scheduledInstant + 1.minutes
        broadcast(Intent.ACTION_TIME_CHANGED)

        assertEquals("the next rung is the repeat, never the first again", repeat, alarmRung())
        assertEquals("armed where it belongs, not at the old trigger", repeat, triggerAt())
        assertTrue("never in the past: no negative delay", triggerAt() >= f.arming.clock.now)
        assertEquals("nothing fired again", 1, f.arming.count(EventType.ALARM_FIRED, "a"))
        assertEquals("and nothing was written", log, f.arming.eventLog("a"))
        // A fire for the rung that already fired is a leftover.
        assertEquals(FireOutcome.NotExpected, f.arming.handler.onFire(31, a.scheduledInstant))
        assertEquals(1, f.arming.count(EventType.ALARM_FIRED, "a"))
        // When the clock reaches the repeat it fires once, and that is the second rung.
        f.arming.clock.now = repeat
        assertTrue(f.arming.handler.onFire(31, repeat) is FireOutcome.Fired)
        assertEquals(2, f.arming.count(EventType.ALARM_FIRED, "a"))
    }

    // Golden scenario 14 (forward). Rungs that become overdue are armed for now, not at an instant in the past, and
    // the fire path decides how a late rung is presented: here beyond the window, so a silent notice.
    @Test
    fun `scenario 14 clock set forward arms the overdue rung for now and the fire path decides`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        assertEquals(a.scheduledInstant + 5.minutes, alarmRung())
        assertEquals(a.scheduledInstant + 5.minutes, triggerAt())

        f.arming.clock.now = a.scheduledInstant + 1.hours
        broadcast(Intent.ACTION_TIME_CHANGED)

        assertEquals("armed for now, not in the past", f.arming.clock.now, triggerAt())
        assertEquals("the rung's own instant is still the repeat", a.scheduledInstant + 5.minutes, alarmRung())
        assertEquals("one ALARM_FIRED so far", 1, f.arming.count(EventType.ALARM_FIRED, "a"))
        val outcome = f.arming.handler.onFire(31, alarmRung()) as FireOutcome.Fired
        assertEquals(
            "beyond the catch up window: a silent notice",
            Presentation.SILENT_NOTICE,
            outcome.rung.presentation,
        )
        assertEquals(2, f.arming.count(EventType.ALARM_FIRED, "a"))
    }

    // A clock change that changes nothing about what is armed writes nothing: ensureArmed is idempotent.
    @Test
    fun `a clock change with the right alarm armed changes nothing`() {
        f.due("a", Criticality.STANDARD, slot = 31)
        val shapes = f.arming.shapes()
        val log = f.arming.eventLog("a")

        broadcast(Intent.ACTION_TIME_CHANGED)

        assertEquals(shapes, f.arming.shapes())
        assertEquals(log, f.arming.eventLog("a"))
    }

    // --- an app update

    // AOSP keeps an updated app's alarms (ADR 0067), but a platform that cleared them must be handled too: the
    // pass arms the next rung either way, and records the new version code, so the watchdog that follows finds
    // nothing wrong and writes no WATCHDOG_REPAIR for an update the broadcast already answered.
    @Test
    fun `an update that cleared the alarm is re armed and the version recorded`() {
        f.due("a", Criticality.STANDARD, slot = 31)
        f.arming.loseAlarm(31)
        assertEquals(0, f.arming.alarms().size)
        f.arming.versionCode = 101

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertEquals("the alarm is armed again", 1, f.arming.alarms().size)
        assertEquals(31, f.arming.requestCode(f.arming.alarms().single()))
        assertEquals(
            101L,
            f.arming.armed
                .current()
                ?.versionCode,
        )
        f.arming.clock.now = f.arming.clock.now + 1.minutes
        val result = f.arming.watchdog.run()
        assertTrue("the watchdog finds nothing to repair: ${result.evidence}", result.evidence.isEmpty())
        assertEquals(0, f.arming.count(EventType.WATCHDOG_REPAIR, "a"))
    }

    @Test
    fun `an update that kept the alarm leaves it exactly as it was`() {
        f.due("a", Criticality.STANDARD, slot = 31)
        val shapes = f.arming.shapes()
        val log = f.arming.eventLog("a")
        f.arming.versionCode = 101

        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertEquals("the same alarm", shapes, f.arming.shapes())
        assertEquals("nothing written", log, f.arming.eventLog("a"))
        assertEquals(
            "but the version is recorded",
            101L,
            f.arming.armed
                .current()
                ?.versionCode,
        )
    }

    // --- a grant of exact alarm access (API 31 and later)

    @Test
    @Config(sdk = [31, 33, 34, 36])
    fun `a grant of exact alarm access upgrades out of tier 1`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        f.due("a", Criticality.STANDARD, slot = 31)
        val inexact = f.arming.shapes().single()
        assertTrue("tier 1: an inexact alarm", inexact.allowWhileIdle && !inexact.alarmClock)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        broadcast(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)

        val exact = f.arming.shapes().single()
        assertTrue("upgraded to an exact alarm: $exact", exact.alarmClock)
        assertEquals("the same rung and slot", inexact.requestCode, exact.requestCode)
        assertEquals(inexact.triggerAtMs, exact.triggerAtMs)
        assertEquals(1, f.arming.alarms().size)
    }

    // The broadcast is sent on a grant and never on a revocation, and capability is never learned from it. A stray
    // one while exact capability is still absent upgrades nothing.
    @Test
    @Config(sdk = [31, 33, 34, 36])
    fun `the grant broadcast without the capability stays in tier 1`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        f.due("a", Criticality.STANDARD, slot = 31)

        broadcast(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)

        assertFalse(
            f.arming
                .shapes()
                .single()
                .alarmClock,
        )
    }

    // --- only the expected actions

    private val queryAll =
        WorkQuery.fromStates(WorkInfo.State.entries.toList())

    @Test
    fun `an action that is not expected enqueues nothing`() {
        f.due("a", Criticality.STANDARD, slot = 31)
        f.arming.clearAlarms()
        val receiver = SystemBroadcastReceiver()
        for (
        action in
        listOf(
            "com.example.UNEXPECTED",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.USER_PRESENT",
        )
        ) {
            shadowOf(receiver).onReceive(context, Intent(action), AtomicBoolean())
        }
        shadowOf(receiver).onReceive(context, Intent(), AtomicBoolean())

        assertEquals("no work was enqueued", 0, workManager.getWorkInfos(queryAll).get().size)
        assertEquals("and nothing was armed", 0, f.arming.alarms().size)
        // The positive control: an expected action does enqueue, so the check above can fail.
        broadcast(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(1, workManager.getWorkInfos(queryAll).get().size)
    }

    private companion object {
        const val MAX_FIRES = 8
    }
}
