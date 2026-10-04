package com.momtime.android.arming

import android.content.Context
import android.os.Build
import com.momtime.android.capability.DeliveryMechanism
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
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
import kotlin.time.Duration.Companion.hours

/**
 * Golden scenario 3, Android half, and the `SecurityException` of the reworded plan item (ADR 0050, ADR 0053),
 * against the one entry point, "ensure armed". The delivery tier changes which mechanism realises a rung and
 * never the ladder, so a downgrade is: the same rung, armed through another call, and nothing written to the log.
 *
 * Below API 31 exact alarms need no permission, so capability cannot be lost there, and the tests say what holds
 * at each SDK instead of skipping it. This covers the entry point. The same scenario end to end through the
 * watchdog worker lands in PR 4, and the scenario's row stays partial until then.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 36])
class CapabilityChangeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val canLoseExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @After
    fun tearDown() = fixture.close()

    private fun single(): ShadowAlarmManager.ScheduledAlarm {
        assertEquals("the platform must hold exactly one alarm", 1, fixture.alarms().size)
        return fixture.alarms().single()
    }

    // Arm with exact allowed, then the platform stops allowing it. The alarm is lost with it, as it would be
    // when the app is killed for the revocation. Nothing is broadcast. The next ensure armed resolves again.
    @Test
    fun `exact revoked then ensure armed`() {
        val a = fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        assertNotNull("armed exactly while exact was allowed", single().alarmClockInfo)
        assertEquals(DeliveryCapability.TIER_2, fixture.resolver.current.capability)
        val logBefore = fixture.eventLog("a")

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        fixture.clearAlarms()
        val result = fixture.coordinator.ensureArmed() as EnsureResult.Armed

        if (canLoseExact) {
            assertEquals(DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE, result.mechanism)
            assertEquals(DeliveryCapability.TIER_1, fixture.resolver.current.capability)
            val alarm = single()
            assertNull("an inexact alarm, not an alarm clock", alarm.alarmClockInfo)
            assertTrue(alarm.isAllowWhileIdle)
            assertEquals("the same rung", t0.toEpochMilliseconds(), alarm.triggerAtMs)
            assertEquals("the same code", a.alarmSlot, fixture.requestCode(alarm))
            assertFalse("the record says exact was lost", checkNotNull(fixture.armed.current()).exactAllowed)
        } else {
            assertEquals(DeliveryMechanism.SET_ALARM_CLOCK, result.mechanism)
            assertNotNull("capability cannot be lost below API 31", single().alarmClockInfo)
            assertTrue(checkNotNull(fixture.armed.current()).exactAllowed)
        }
        assertEquals("a downgrade writes nothing to the log", logBefore, fixture.eventLog("a"))
        assertEquals(OccurrenceState.PENDING, fixture.occurrences.findById("a")?.state)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        fixture.coordinator.ensureArmed()

        assertNotNull("exact again once it is allowed again", single().alarmClockInfo)
        assertEquals(logBefore, fixture.eventLog("a"))
    }

    // The platform refuses an exact alarm although the capability said yes (a stale grant). Resolving again
    // still says exact, so the refusal is trusted, and the alarm is armed inexactly rather than not at all.
    @Test
    fun `refused exact arms inexactly`() {
        val a = fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.api.refuseExact = true

        val result = fixture.coordinator.ensureArmed() as EnsureResult.Armed

        assertEquals("exact was tried first", 1, fixture.api.exactAttempts)
        assertEquals(DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE, result.mechanism)
        assertEquals(DeliveryCapability.TIER_1, fixture.resolver.current.capability)
        val alarm = single()
        assertNull(alarm.alarmClockInfo)
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(a.alarmSlot, fixture.requestCode(alarm))
        assertFalse(checkNotNull(fixture.armed.current()).exactAllowed)

        // The refusal is not remembered: the next pass reads the platform again, and exact is tried again.
        fixture.api.refuseExact = false
        val again = fixture.coordinator.ensureArmed() as EnsureResult.Armed
        assertEquals(DeliveryMechanism.SET_ALARM_CLOCK, again.mechanism)
        assertNotNull(single().alarmClockInfo)
    }

    // The same, with the platform now reporting no capability: resolving again gives the inexact tier itself.
    @Test
    fun `refused exact and capability gone`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.clearAlarms()
        fixture.api.refuseExact = true
        // The platform changes its answer between the pass's own read and the call.
        if (canLoseExact) ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val result = fixture.coordinator.ensureArmed() as EnsureResult.Armed

        assertEquals(DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE, result.mechanism)
        assertNull(single().alarmClockInfo)
    }
}
