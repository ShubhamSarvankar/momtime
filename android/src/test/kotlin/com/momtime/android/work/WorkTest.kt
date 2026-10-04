package com.momtime.android.work

import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.momtime.android.arming.ArmingEntryPoint
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.FiredRung
import com.momtime.android.arming.t0
import com.momtime.android.capability.DeliveryMechanism
import com.momtime.android.data.template
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The workers, through `WorkManager`'s own machinery and its test driver (ADR 0057, ADR 0058), at SDK 29, 31, 33
 * and 36. The test driver stands in for the system's job scheduler: it says the period has elapsed, and
 * `WorkManager` creates the worker and runs it, so the worker class, the entry point and the pass are all
 * exercised. What it cannot show is when a real device runs the job under Doze (`MANUAL_CHECKS.md` P2-13).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 36])
class WorkTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val canLoseExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        WorkEntryPoint.provider = { fixture.passes }
        // The real application installs both entry points, so a worker that reached for the fire path would find it.
        ArmingEntryPoint.provider = { fixture.handler }
    }

    @After
    fun tearDown() {
        WorkEntryPoint.provider = null
        ArmingEntryPoint.provider = null
        fixture.close()
    }

    private fun info(name: String): WorkInfo = workManager.getWorkInfosForUniqueWork(name).get().single()

    private fun runPeriodic(name: String) {
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        driver.setPeriodDelayMet(info(name).id)
    }

    @Test
    fun `the watchdog is unique periodic work at the fifteen minute floor`() {
        Work.scheduleWatchdog(workManager)

        val periodicity = checkNotNull(info(Work.WATCHDOG).periodicityInfo)
        assertEquals(TimeUnit.MINUTES.toMillis(15), periodicity.repeatIntervalMillis)
        assertEquals(WorkInfo.State.ENQUEUED, info(Work.WATCHDOG).state)
    }

    // Enqueueing again, as every start does, must not replace the job: a replaced job has a new id and a new
    // timer, so a process that restarts more often than every 15 minutes would keep the watchdog from running.
    @Test
    fun `enqueueing the watchdog again keeps the job and its timer`() {
        Work.scheduleWatchdog(workManager)
        val first = info(Work.WATCHDOG)

        Work.scheduleWatchdog(workManager)
        Work.scheduleAll(context)

        val again = info(Work.WATCHDOG)
        assertEquals("the same job, not a replacement", first.id, again.id)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(Work.WATCHDOG).get().size)
    }

    @Test
    fun `the start enqueues both periodic jobs`() {
        Work.scheduleAll(context)

        assertEquals(
            TimeUnit.MINUTES.toMillis(15),
            checkNotNull(info(Work.WATCHDOG).periodicityInfo).repeatIntervalMillis,
        )
        assertEquals(
            TimeUnit.HOURS.toMillis(24),
            checkNotNull(info(Work.MATERIALISE_DAILY).periodicityInfo).repeatIntervalMillis,
        )
    }

    // The worker runs the pass: a cleared alarm is repaired by the job itself, with nothing else involved.
    @Test
    fun `the worker repairs a cleared alarm`() {
        fixture.seed("a", Criticality.CRITICAL, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.loseAlarm(31)
        Work.scheduleWatchdog(workManager)

        runPeriodic(Work.WATCHDOG)

        assertEquals("the alarm is armed again", 1, fixture.alarms().size)
        assertEquals(1, fixture.count(EventType.WATCHDOG_REPAIR, "a"))
        assertEquals("the job goes on", WorkInfo.State.ENQUEUED, info(Work.WATCHDOG).state)
    }

    // The worker never starts the ringer: an overdue rung is armed for now and the alarm path rings it.
    @Test
    fun `the worker arms an overdue rung and does not ring it`() {
        fixture.seed("a", Criticality.CRITICAL, t0, slot = 31)
        fixture.clock.now = t0 + 20.minutes
        Work.scheduleWatchdog(workManager)

        runPeriodic(Work.WATCHDOG)

        assertEquals(fixture.clock.now.toEpochMilliseconds(), fixture.alarms().single().triggerAtMs)
        assertEquals(emptyList<FiredRung>(), fixture.delivery.delivered)
        assertEquals(0, fixture.count(EventType.ALARM_FIRED, "a"))
    }

    // A transient failure asks for a retry. It never fails the periodic work, which would end the watchdog.
    @Test
    fun `a failing pass does not fail the periodic work`() {
        WorkEntryPoint.provider = { throw IllegalStateException("transient") }
        Work.scheduleWatchdog(workManager)

        runPeriodic(Work.WATCHDOG)

        val state = info(Work.WATCHDOG).state
        assertNotEquals("a failed periodic job never runs again", WorkInfo.State.FAILED, state)
        assertEquals(WorkInfo.State.ENQUEUED, state)
    }

    // Golden scenario 3 end to end through the worker. Arm with exact allowed, take exact capability away,
    // clear the alarms, send no broadcast, run the watchdog. The tier falls to 1 and the alarm is inexact.
    @Test
    fun `exact revoked then the watchdog runs`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        assertNotNull("armed exactly while exact was allowed", fixture.alarms().single().alarmClockInfo)
        assertEquals(DeliveryCapability.TIER_2, fixture.resolver.current.capability)
        val logBefore = fixture.eventLog("a")

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        fixture.loseAlarm(31)
        Work.scheduleWatchdog(workManager)
        runPeriodic(Work.WATCHDOG)

        if (canLoseExact) {
            assertEquals(DeliveryCapability.TIER_1, fixture.resolver.current.capability)
            assertEquals(DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE, fixture.resolver.current.mechanism)
            val alarm = fixture.alarms().single()
            assertNull("an inexact alarm, not an alarm clock", alarm.alarmClockInfo)
            assertTrue(alarm.isAllowWhileIdle)
            assertEquals("the same rung", t0.toEpochMilliseconds(), alarm.triggerAtMs)
            assertEquals("the same code", 31, fixture.requestCode(alarm))
            assertFalse("the record says exact was lost", checkNotNull(fixture.armed.current()).exactAllowed)
            assertEquals(
                "the exact event delta: one repair and nothing else",
                listOf(EventType.WATCHDOG_REPAIR to "a"),
                fixture.eventDelta("a", before = logBefore),
            )
        } else {
            assertEquals(DeliveryMechanism.SET_ALARM_CLOCK, fixture.resolver.current.mechanism)
            assertNotNull("capability cannot be lost below API 31", fixture.alarms().single().alarmClockInfo)
            assertEquals(
                "only the cleared alarm was repaired",
                listOf(EventType.WATCHDOG_REPAIR to "a"),
                fixture.eventDelta("a", before = logBefore),
            )
        }
        assertEquals("nothing was rung", emptyList<FiredRung>(), fixture.delivery.delivered)
        assertEquals(1, fixture.alarms().size)
    }

    // The daily job regenerates the window, then ensures an alarm exists: the new occurrence may be earlier
    // than anything armed, and a window with nothing armed for it would be silent.
    @Test
    fun `the daily materialisation creates the window and arms the first rung`() {
        fixture.graph.get<ScheduleTemplateRepository>().insert(template("daily"))
        fixture.clock.now = t0
        Work.scheduleDailyMaterialisation(workManager)

        runPeriodic(Work.MATERIALISE_DAILY)

        val created = fixture.occurrences.findForTemplate("daily")
        assertEquals("48 hours of a daily template", 2, created.size)
        val first = created.minBy { it.scheduledInstant }
        val alarm = fixture.alarms().single()
        assertEquals(first.scheduledInstant.toEpochMilliseconds(), alarm.triggerAtMs)
        assertEquals(first.alarmSlot, fixture.requestCode(alarm))

        runPeriodic(Work.MATERIALISE_DAILY)
        assertEquals("a second run adds nothing", 2, fixture.occurrences.findForTemplate("daily").size)
        assertEquals(1, fixture.alarms().size)
    }

    // The one time run the template edit path will call. It is unique work, appended after one in progress.
    @Test
    fun `an edit enqueues one materialisation run`() {
        fixture.graph.get<ScheduleTemplateRepository>().insert(template("daily"))
        fixture.clock.now = t0

        Work.enqueueMaterialisation(workManager)
        Work.enqueueMaterialisation(workManager)

        val runs = workManager.getWorkInfosForUniqueWork(Work.MATERIALISE_NOW).get()
        assertTrue("every run finished", runs.all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(2, fixture.occurrences.findForTemplate("daily").size)
        assertEquals(1, fixture.alarms().size)
    }

    // WorkManager arms an alarm of its own on older releases, to notice a force stop. It is not the alarm path's
    // and is not counted against "exactly one alarm" (`ArmingFixture.alarms`), so the test says what it is.
    @Test
    fun `the only other alarm is WorkManager's own`() {
        Work.scheduleAll(context)

        val others = fixture.allAlarms() - fixture.alarms().toSet()

        val description = others.joinToString { "${it.triggerAtMs} ${it.operation}" }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            assertEquals("WorkManager's own alarm below API 30: $description", 1, others.size)
        } else {
            assertEquals("no alarm but ours from API 30: $description", 0, others.size)
        }
    }
}
