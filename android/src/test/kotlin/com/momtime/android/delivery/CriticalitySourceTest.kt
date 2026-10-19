package com.momtime.android.delivery

import android.content.Context
import android.os.Build
import com.momtime.android.arming.t0
import com.momtime.android.data.template
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.VibrationPattern
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.ReconcileCommand
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.QuietHours
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Every behaviour that depends on criticality reads the occurrence's own, never its template's (ADR 0079 item 6).
 * One fixture for every case: a Critical template, materialised through the real materialiser, and then the
 * template row set to Gentle directly in the database, so that the two values differ and each behaviour shows
 * which one it read. Each case is the Critical behaviour, through the production entry point for that behaviour:
 * the fire path and `ensureArmed` for the ladder, `ReconcileCommand` for grace, the delivery port for the channel
 * and the vibration, and the fire path's decision for the budget and quiet hours.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class CriticalitySourceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val sessions: RingSessions get() = f.arming.graph.get()
    private val canLoseExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @After
    fun tearDown() = f.close()

    private fun path(occurrenceId: String) = f.telemetryOf(occurrenceId).deliveryPath

    private fun state(id: String): OccurrenceState? =
        f.arming.occurrences
            .findById(id)
            ?.state

    /**
     * The divergent occurrence: materialised from a Critical template whose row is then set to Gentle, with no
     * command (none can change a criticality yet). Its time of day is `t0` in the test zone, so the materialiser
     * creates it for the 1st of March at `t0` and a second one for the 2nd. Returns the first.
     */
    private fun divergent(): Occurrence {
        val templates: ScheduleTemplateRepository = f.arming.graph.get()
        templates.insert(template(TEMPLATE).copy(criticality = Criticality.CRITICAL, timeOfDay = LocalTime(13, 30)))
        f.arming.clock.now = t0 - 1.hours
        assertEquals(
            2,
            f.arming.graph
                .get<MaterialiseCommand>()
                .dispatch(t0 - 1.hours),
        )
        val occurrence =
            f.arming.occurrences
                .findOpen()
                .single { it.localDate == LocalDate(2026, 3, 1) }
        assertEquals(t0, occurrence.scheduledInstant)
        f.arming.graph.factory
            .createDriver()
            .execute(null, "UPDATE schedule_template SET criticality = 'GENTLE' WHERE id = '$TEMPLATE'", 0)
        assertEquals("the template now says Gentle", Criticality.GENTLE, templates.findById(TEMPLATE)?.criticality)
        assertEquals("the occurrence still says Critical", Criticality.CRITICAL, occurrence.criticality)
        f.arming.coordinator.ensureArmed()
        f.arming.clock.now = t0
        return occurrence
    }

    // The Critical ladder: after the first rung the repeat is armed five minutes on. Gentle's ladder has no second
    // rung, so under it nothing of this occurrence would be armed (the 2nd of March's rung would be, a day on).
    @Test
    fun `the ladder`() {
        val o = divergent()

        f.fire(o)

        val alarm = f.arming.alarms().single()
        assertEquals(
            "the second rung is armed five minutes on",
            (t0 + 5.minutes).toEpochMilliseconds(),
            alarm.triggerAtMs,
        )
        assertEquals(o.alarmSlot, f.arming.requestCode(alarm))
    }

    // Critical grace is two hours; Gentle's is the end of the local day. Through the command the watchdog and the
    // fire path dispatch.
    @Test
    fun `grace`() {
        val o = divergent()
        val reconcile: ReconcileCommand = f.arming.graph.get()

        assertEquals("not before two hours", 0, reconcile.dispatch(t0 + 2.hours - 1.milliseconds))
        assertEquals(OccurrenceState.PENDING, state(o.id))
        assertEquals("missed at two hours", 1, reconcile.dispatch(t0 + 2.hours))
        assertEquals(OccurrenceState.MISSED, state(o.id))
        assertEquals(
            t0 + 2.hours,
            f.arming
                .eventsOf(o.id, EventType.MISSED)
                .single()
                .effectiveAt,
        )
    }

    // The Critical channel, on the ring path and, where exact capability can be lost, on the plain path too.
    @Test
    fun `the channel`() {
        val o = divergent()

        f.fire(o)

        assertEquals("RING", path(o.id))
        assertEquals(
            NotificationChannels.CRITICAL,
            f.ringer.starts
                .single()
                .channelId,
        )
        if (canLoseExact) {
            sessions.end()
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            val next =
                f.arming.occurrences
                    .findOpen()
                    .single { it.localDate == LocalDate(2026, 3, 2) }
            f.arming.clock.now = next.scheduledInstant - 1.hours
            f.arming.coordinator.ensureArmed()
            f.fire(next)
            assertEquals("PLAIN", path(next.id))
            assertEquals(NotificationChannels.CRITICAL, f.channelOf(next.alarmSlot))
        }
    }

    // Critical's default pattern; Gentle's is none.
    @Test
    fun `the default vibration`() {
        val o = divergent()

        f.fire(o)

        assertEquals(
            VibrationPattern.URGENT,
            f.ringer.starts
                .single()
                .vibration,
        )
    }

    // Critical is never budget limited: with the one ring of the day spent, it still rings. Gentle would be silent.
    @Test
    fun `the budget exemption`() {
        val o = divergent()
        f.settings.updateRingGradeDailyBudget(1)
        val s = f.due("s", Criticality.STANDARD, slot = 90)
        f.fire(s)
        sessions.end()
        assertEquals("the budget is spent", 1, f.budget.current().ringCount)

        f.fire(o)

        assertEquals("RING", path(o.id))
        assertEquals(2, f.ringer.starts.size)
        assertEquals(2, f.budget.current().ringCount)
    }

    // Critical overrides quiet hours: t0 is 08:00 in the device zone. Gentle would be silent.
    @Test
    fun `quiet hours`() {
        val o = divergent()
        f.settings.updateQuietHours(QuietHours(LocalTime(7, 0), LocalTime(9, 0)))

        f.fire(o)

        assertEquals("RING", path(o.id))
        assertEquals(
            NotificationChannels.CRITICAL,
            f.ringer.starts
                .single()
                .channelId,
        )
    }

    private companion object {
        const val TEMPLATE = "tmpl-d"
    }
}
