package com.momtime.android.system

import android.content.Context
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.momtime.android.arming.AlarmIntents
import com.momtime.android.arming.FireOutcome
import com.momtime.android.arming.t0
import com.momtime.android.delivery.DeliveryFixture
import com.momtime.android.delivery.RingController
import com.momtime.android.delivery.finished
import com.momtime.android.delivery.withPendingResult
import com.momtime.android.work.Work
import com.momtime.android.work.WorkEntryPoint
import com.momtime.shared.data.OccurrenceActionCommand
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.OccurrenceAction
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Golden scenario 8, through the broadcast (ADR 0068): the device changes zone, the receiver enqueues the pass, and
 * the pass moves every open occurrence of an active template to its wall clock time in the new zone, never to before
 * the change, and arms what is next. The occurrence on 2026-03-01 is a daily 08:00 dose of a template set up in
 * Kolkata. East (Tokyo, UTC+9) its wall clock time there is 23:00 UTC the day before; west (New York, UTC-5) it is
 * 13:00 UTC.
 *
 * The zone is the fixture's `deviceZone`, read only through the `DeviceZone` seam. Nothing here shows a phone sending
 * the broadcast or changing zone (`MANUAL_CHECKS.md` P2-30).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 33, 36])
class ZoneChangeBroadcastTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val newYork = TimeZone.of("America/New_York")
    private val date = LocalDate(2026, 3, 1)
    private val tokyoEight = LocalDateTime(date, LocalTime(8, 0)).toInstant(tokyo)
    private val newYorkEight = LocalDateTime(date, LocalTime(8, 0)).toInstant(newYork)
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

    /** The device moves to [zone] and the system sends the zone change broadcast. */
    private fun travelTo(zone: TimeZone) {
        f.deviceZone = zone
        val receiver = SystemBroadcastReceiver()
        val pending = withPendingResult(receiver)
        shadowOf(receiver).onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED), AtomicBoolean())
        assertTrue("the receiver did not finish the broadcast", finished(pending))
    }

    private fun occurrence(id: String) = checkNotNull(f.arming.occurrences.findById(id))

    private fun armed() =
        run {
            assertEquals("exactly one alarm is armed", 1, f.arming.alarms().size)
            f.arming.alarms().single()
        }

    private fun armedRung(): Instant =
        Instant.fromEpochMilliseconds(
            shadowOf(f.arming.operation(armed())).savedIntent.getLongExtra(AlarmIntents.EXTRA_RUNG_MILLIS, 0L),
        )

    @Test
    fun `the receiver answers a zone change with the pass`() {
        f.due("a", Criticality.STANDARD, slot = 31)

        travelTo(tokyo)

        assertEquals(
            WorkInfo.State.SUCCEEDED,
            workManager
                .getWorkInfosForUniqueWork(Work.SYSTEM_QUEUE)
                .get()
                .single()
                .state,
        )
        assertEquals("the template follows the device", tokyo, checkNotNull(templates().findById("tmpl-a")).timeZoneId)
    }

    private fun templates(): ScheduleTemplateRepository = f.arming.graph.get()

    // --- east

    @Test
    fun `scenario 8 east a dose not yet due becomes due at the change and the alarm is armed for it`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        val change = t0 - 1.hours
        f.arming.clock.now = change
        assertEquals("the dose was not yet due", t0, a.scheduledInstant)

        travelTo(tokyo)

        val moved = occurrence("a")
        assertEquals("due at the change, not at the unclamped Tokyo time", change, moved.scheduledInstant)
        assertTrue("the unclamped time would already be past its grace", tokyoEight + 2.hours < change)
        assertEquals(OccurrenceState.PENDING, moved.state)
        assertEquals(0, f.arming.count(EventType.MISSED, "a"))
        assertEquals("the same slot, so the same request code", 31, moved.alarmSlot)
        assertEquals(31, f.arming.requestCode(armed()))
        assertEquals("the first rung follows the new instant", change, armedRung())
        assertEquals("armed for now", change.toEpochMilliseconds(), armed().triggerAtMs)
        // The fire path decides as usual: due now, within the catch up window, so it rings.
        val outcome = f.arming.handler.onFire(31, armedRung())
        assertTrue(outcome is FireOutcome.Fired)
        assertEquals(1, f.ringer.starts.size)
    }

    // --- west

    @Test
    fun `scenario 8 west a dose moves later and the alarm follows it`() {
        f.due("a", Criticality.CRITICAL, slot = 31)
        f.arming.clock.now = t0 - 1.hours

        travelTo(newYork)

        assertEquals(newYorkEight, occurrence("a").scheduledInstant)
        assertEquals(newYorkEight, armedRung())
        assertEquals(newYorkEight.toEpochMilliseconds(), armed().triggerAtMs)
        assertEquals(31, f.arming.requestCode(armed()))
        assertEquals(0, f.arming.count(EventType.ALARM_FIRED, "a"))
    }

    // Rungs that already fired stay consumed under the count rule; the remaining rungs follow the new instant.
    @Test
    fun `rungs that fired stay consumed and the rest follow the new instant`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        assertEquals(1, f.arming.count(EventType.ALARM_FIRED, "a"))
        f.arming.clock.now = t0 + 1.minutes

        travelTo(newYork)

        assertEquals(1, f.arming.count(EventType.ALARM_FIRED, "a"))
        assertEquals("the repeat, five minutes after the new instant", newYorkEight + 5.minutes, armedRung())
    }

    // --- the other states

    @Test
    fun `an occurrence that is already terminal is untouched`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.arming.clock.now = t0 + 1.minutes
        f.arming.complete("a")
        val before = occurrence("a")
        val log = f.arming.eventLog("a")

        travelTo(newYork)

        assertEquals(before, occurrence("a"))
        assertEquals("nothing was written", log, f.arming.eventLog("a"))
    }

    // The snooze's end is an absolute instant decided when she snoozed: the occurrence moves, the snooze does not.
    @Test
    fun `a snoozed occurrence moves and its snooze end stays where it was decided`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.arming.clock.now = t0 + 1.minutes
        f.arming.graph
            .get<RingController>()
            .act("a", OccurrenceAction.SNOOZE)
        val end = t0 + 11.minutes
        assertEquals(end, armedRung())

        travelTo(newYork)

        assertEquals(OccurrenceState.SNOOZED, occurrence("a").state)
        assertEquals("the occurrence moved", newYorkEight, occurrence("a").scheduledInstant)
        val command: OccurrenceActionCommand = f.arming.graph.get()
        assertEquals("the snooze still ends where it was decided", end, command.runningSnoozeEnd(occurrence("a")))
        assertEquals("and the armed alarm is still its end", end, armedRung())
        assertEquals(end.toEpochMilliseconds(), armed().triggerAtMs)
    }

    // --- what is materialised afterwards

    @Test
    fun `later materialisation uses the new zone`() {
        f.due("a", Criticality.STANDARD, slot = 31)
        f.arming.clock.now = t0 - 1.hours

        travelTo(tokyo)

        val created =
            f.arming.occurrences
                .findForTemplate("tmpl-a")
                .filter { it.id != "a" }
        assertTrue("the window was materialised in the new zone", created.isNotEmpty())
        for (o in created) {
            assertEquals(tokyo, o.timeZoneId)
            assertEquals(LocalDateTime(o.localDate, LocalTime(8, 0)).toInstant(tokyo), o.scheduledInstant)
        }
    }

    // The broadcast comes twice (a device may send it more than once for one change): the second finds everything in
    // the new zone and moves nothing. The overdue first rung is armed for now each pass, so its trigger follows the
    // clock; the occurrence and the rung it is armed for do not move.
    @Test
    fun `a second zone change broadcast for the same zone moves nothing`() {
        f.due("a", Criticality.CRITICAL, slot = 31)
        f.arming.clock.now = t0 - 1.hours
        travelTo(tokyo)
        val after = occurrence("a")
        val rung = armedRung()
        f.arming.clock.now = t0 - 30.minutes

        travelTo(tokyo)

        assertEquals("not moved again by the later change", after, occurrence("a"))
        assertEquals(rung, armedRung())
    }
}
