package com.momtime.android.arming

import android.content.Context
import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The fire path (ADR 0053) for alarms that must not ring: an alarm left over from before a reset, a
 * completion or a re arm. Each writes nothing and delivers nothing, and arms the next alarm all the same.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 36])
class StaleFireTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)

    @After
    fun tearDown() = fixture.close()

    // After a reset or a restore the slot of a fired alarm can name no occurrence (decision 21).
    @Test
    fun `an unknown slot writes nothing and does not throw`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        val logBefore = fixture.eventLog("a")

        val outcome = runCatching { fixture.handler.onFire(999, t0) }

        assertTrue("an unknown slot threw: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(FireOutcome.UnknownSlot, outcome.getOrThrow())
        assertEquals("nothing written", logBefore, fixture.eventLog("a"))
        assertEquals("nothing delivered", emptyList<FiredRung>(), fixture.delivery.delivered)
        assertEquals("and the next alarm is still armed", 1, fixture.alarms().size)
        assertEquals(31, fixture.requestCode(fixture.alarms().single()))
    }

    // The alarm for a rung was armed, then the occurrence was completed, and the alarm fired anyway.
    @Test
    fun `a terminal occurrence writes nothing`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.complete("a")
        fixture.clock.now = t0
        val logBefore = fixture.eventLog("a")

        val outcome = fixture.handler.onFire(31, t0)

        assertEquals(FireOutcome.Terminal, outcome)
        assertEquals("nothing written", logBefore, fixture.eventLog("a"))
        assertEquals(0, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
        assertEquals("nothing delivered", emptyList<FiredRung>(), fixture.delivery.delivered)
        assertEquals("the stale alarm is gone and nothing else is pending", 0, fixture.alarms().size)
    }

    // The rung that arrives has already fired, or is not the one the log says is next.
    @Test
    fun `a rung that is not the expected one writes nothing`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()
        fixture.handler.onFire(31, t0)
        val logBefore = fixture.eventLog("a")
        val deliveredBefore = fixture.delivery.delivered

        val again = fixture.handler.onFire(31, t0)
        val wrongInstant = fixture.handler.onFire(31, t0 + 1.minutes)

        assertEquals(FireOutcome.NotExpected, again)
        assertEquals(FireOutcome.NotExpected, wrongInstant)
        assertEquals("nothing written", logBefore, fixture.eventLog("a"))
        assertEquals("nothing delivered", deliveredBefore, fixture.delivery.delivered)
        assertEquals("the next rung is still the one armed", 1, fixture.alarms().size)
        assertEquals((t0 + 10.minutes).toEpochMilliseconds(), fixture.alarms().single().triggerAtMs)
    }

    // The platform may deliver the same alarm twice (a retry, a process restart mid broadcast). The second delivery
    // must not advance the fired record a second time: a second ALARM_FIRED would skip a rung, and a second
    // hand over would ring twice.
    @Test
    fun `a duplicate fire is absorbed`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()

        val first = fixture.handler.onFire(31, t0)
        val armedAfterFirst = fixture.shapes()
        val second = fixture.handler.onFire(31, t0)

        assertTrue(first is FireOutcome.Fired)
        assertEquals(FireOutcome.NotExpected, second)
        assertEquals("exactly one ALARM_FIRED", 1, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
        assertEquals("handed over once", 1, fixture.delivery.delivered.size)
        assertEquals("the next rung is unchanged", armedAfterFirst, fixture.shapes())
        assertEquals((t0 + 10.minutes).toEpochMilliseconds(), fixture.alarms().single().triggerAtMs)
        // And the rung after the duplicate is still the second one: it fires as the second, not the third.
        fixture.clock.now = t0 + 10.minutes
        assertTrue(fixture.handler.onFire(31, t0 + 10.minutes) is FireOutcome.Fired)
        assertEquals(2, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
    }

    // ADR 0056: the catch up window decides how a late rung is presented, never whether it is delivered. This one
    // was armed for t0 and fired 31 minutes late (a Tier 1 alarm that Doze deferred): it is handed to delivery as a
    // silent notice, with no ring, and it writes ALARM_FIRED, so the rung is consumed and is not offered again.
    @Test
    fun `an alarm that fires beyond the window is a silent notice`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.clock.now = t0 + 31.minutes

        val outcome = fixture.handler.onFire(31, t0)

        assertTrue("a late rung is delivered: $outcome", outcome is FireOutcome.Fired)
        val rung = EscalationRung(t0, Channel.RING)
        assertEquals(
            "a silent presentation, handed over once",
            listOf(FiredRung("a", 31, rung, Presentation.SILENT_NOTICE)),
            fixture.delivery.delivered,
        )
        assertEquals("ALARM_FIRED consumes the rung", 1, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
        assertEquals(
            "the next rung is armed for now, not the consumed one",
            t0 + 10.minutes,
            fixture.armed.current()?.rungInstant,
        )
        assertEquals(fixture.clock.now.toEpochMilliseconds(), fixture.alarms().single().triggerAtMs)

        // The repeat rung is 21 minutes late, which is within the window: it is presented as policy says.
        val repeat = fixture.handler.onFire(31, t0 + 10.minutes) as FireOutcome.Fired
        assertEquals(Presentation.NORMAL, repeat.rung.presentation)
        assertEquals(2, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
    }

    // The boundary is inclusive for presentation too: exactly 30 minutes late is normal, a millisecond more is not.
    @Test
    fun `a rung exactly at the boundary is normal and one millisecond later it is silent`() {
        fixture.seed("a", Criticality.GENTLE, t0, slot = 31)
        fixture.seed("b", Criticality.GENTLE, t0 + 1.hours, slot = 32)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()

        fixture.clock.now = t0 + 30.minutes
        val atBoundary = fixture.handler.onFire(31, t0) as FireOutcome.Fired
        assertEquals(Presentation.NORMAL, atBoundary.rung.presentation)

        fixture.clock.now = t0 + 1.hours + 30.minutes + 1.milliseconds
        val past = fixture.handler.onFire(32, t0 + 1.hours) as FireOutcome.Fired
        assertEquals(Presentation.SILENT_NOTICE, past.rung.presentation)
    }

    // An alarm that fires after the end of grace finds the occurrence MISSED (Reconcile is dispatched first) and
    // delivers nothing. Beyond the window is not MISSED; the end of grace is (ADR 0030).
    @Test
    fun `an alarm that fires after the end of grace delivers nothing`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.clock.now = t0 + 4.hours

        val outcome = fixture.handler.onFire(31, t0)

        assertEquals(FireOutcome.Terminal, outcome)
        assertEquals(emptyList<FiredRung>(), fixture.delivery.delivered)
        assertEquals(0, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
        assertEquals(1, fixture.eventsOf("a", EventType.MISSED).size)
        assertEquals("and it does not arm itself again", 0, fixture.alarms().size)
    }

    // A boot after a night with the phone off: several occurrences are overdue at once and each gets its own
    // silent notice. That is intended (ADR 0056).
    @Test
    fun `every overdue occurrence gets its own silent notice`() {
        fixture.seed("a", Criticality.GENTLE, t0, slot = 31)
        fixture.seed("b", Criticality.GENTLE, t0 + 1.minutes, slot = 32)
        fixture.clock.now = t0 + 3.hours
        fixture.coordinator.ensureArmed()

        val first = fixture.handler.onFire(31, t0) as FireOutcome.Fired
        val second = fixture.handler.onFire(32, t0 + 1.minutes) as FireOutcome.Fired

        assertEquals(listOf("a", "b"), listOf(first.rung.occurrenceId, second.rung.occurrenceId))
        assertEquals(
            listOf(Presentation.SILENT_NOTICE, Presentation.SILENT_NOTICE),
            fixture.delivery.delivered.map { it.presentation },
        )
        assertEquals(0, fixture.alarms().size)
    }

    @Test
    fun `a spent ladder writes nothing`() {
        fixture.seed("a", Criticality.GENTLE, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()
        fixture.handler.onFire(31, t0)
        val logBefore = fixture.eventLog("a")

        val outcome = fixture.handler.onFire(31, t0)

        assertEquals(FireOutcome.NotExpected, outcome)
        assertEquals(logBefore, fixture.eventLog("a"))
        assertEquals(1, fixture.delivery.delivered.size)
    }

    // The expected rung: the exact delta is one ALARM_FIRED, one hand over to delivery and the next rung armed.
    @Test
    fun `the expected rung fires`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()

        val outcome = fixture.handler.onFire(31, t0)

        assertTrue(outcome is FireOutcome.Fired)
        val fired = fixture.eventsOf("a", EventType.ALARM_FIRED)
        assertEquals(1, fired.size)
        assertEquals(t0, fired.single().deviceTimestamp)
        assertEquals(
            listOf(
                FiredRung(
                    "a",
                    31,
                    com.momtime.shared.domain
                        .EscalationRung(t0, com.momtime.shared.domain.Channel.RING),
                ),
            ),
            fixture.delivery.delivered,
        )
    }
}
