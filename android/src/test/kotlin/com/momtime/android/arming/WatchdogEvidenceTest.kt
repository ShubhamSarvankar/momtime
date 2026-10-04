package com.momtime.android.arming

import com.momtime.android.store.ArmedAlarm
import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.engine.RungSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The evidence that the armed alarm was lost (ADR 0058), as a pure function. Each signal is varied alone from
 * a state in which every other signal is silent, so a signal that stops working fails its own test and no
 * other: the set is compared exactly, in both directions.
 */
class WatchdogEvidenceTest {
    private val rung = t0
    private val expected = RungSelection("a", 31, EscalationRung(rung, Channel.RING))

    /** The state of a correct pass: the record says what is expected, the alarm is there, nothing changed. */
    @Suppress("LongParameterList")
    private fun correct(
        armed: ArmedAlarm? =
            ArmedAlarm(
                alarmSlot = 31,
                rungInstant = rung,
                armedAt = rung - 10.minutes,
                bootCount = 7,
                exactAllowed = true,
            ),
        alarmPresent: Boolean = true,
        bootCountNow: Long = 7,
        exactAllowedNow: Boolean = true,
        appUpdatedAt: kotlin.time.Instant? = rung - 1.hours,
        late: Duration = Duration.ZERO,
    ) = WatchdogObservation(expected, armed, alarmPresent, bootCountNow, exactAllowedNow, appUpdatedAt, rung + late)

    private fun evidence(observation: WatchdogObservation) = WatchdogEvidence.of(observation)

    @Test
    fun `a correct state is no evidence`() {
        assertEquals(emptySet<RepairEvidence>(), evidence(correct()))
    }

    @Test
    fun `no record is evidence by itself`() {
        assertEquals(setOf(RepairEvidence.RECORD_MISSING), evidence(correct(armed = null)))
    }

    @Test
    fun `a record for another slot is a mismatch`() {
        val other = checkNotNull(correct().armed).copy(alarmSlot = 32)
        assertEquals(setOf(RepairEvidence.RECORD_MISMATCH), evidence(correct(armed = other)))
    }

    @Test
    fun `a record for another rung is a mismatch`() {
        val other = checkNotNull(correct().armed).copy(rungInstant = rung + 5.minutes)
        assertEquals(setOf(RepairEvidence.RECORD_MISMATCH), evidence(correct(armed = other)))
    }

    @Test
    fun `an alarm the probe cannot find is evidence by itself`() {
        assertEquals(setOf(RepairEvidence.ALARM_ABSENT), evidence(correct(alarmPresent = false)))
    }

    @Test
    fun `a changed boot count is evidence by itself`() {
        assertEquals(setOf(RepairEvidence.BOOT_COUNT_CHANGED), evidence(correct(bootCountNow = 8)))
    }

    // The platform not saying, at either end, is not a restart.
    @Test
    fun `an unreported boot count is not evidence`() {
        val unknownAtArm = checkNotNull(correct().armed).copy(bootCount = -1)
        assertEquals(emptySet<RepairEvidence>(), evidence(correct(armed = unknownAtArm)))
        assertEquals(emptySet<RepairEvidence>(), evidence(correct(bootCountNow = -1)))
    }

    @Test
    fun `a changed exact capability is evidence in either direction`() {
        assertEquals(setOf(RepairEvidence.EXACT_CAPABILITY_CHANGED), evidence(correct(exactAllowedNow = false)))
        val inexactAtArm = checkNotNull(correct().armed).copy(exactAllowed = false)
        assertEquals(
            setOf(RepairEvidence.EXACT_CAPABILITY_CHANGED),
            evidence(correct(armed = inexactAtArm, exactAllowedNow = true)),
        )
    }

    @Test
    fun `an update after the alarm was armed is evidence by itself`() {
        val armedAt = checkNotNull(correct().armed).armedAt
        assertEquals(
            setOf(RepairEvidence.APP_UPDATED),
            evidence(correct(appUpdatedAt = armedAt + 1.milliseconds)),
        )
        assertEquals(emptySet<RepairEvidence>(), evidence(correct(appUpdatedAt = armedAt)))
        assertEquals(emptySet<RepairEvidence>(), evidence(correct(appUpdatedAt = null)))
    }

    // An exact alarm fires within seconds: more than two minutes late is evidence, two minutes is not.
    @Test
    fun `an exact rung is overdue after two minutes`() {
        assertEquals(emptySet<RepairEvidence>(), evidence(correct(late = WatchdogEvidence.EXACT_TOLERANCE)))
        assertEquals(
            setOf(RepairEvidence.RUNG_OVERDUE),
            evidence(correct(late = WatchdogEvidence.EXACT_TOLERANCE + 1.milliseconds)),
        )
    }

    // An inexact alarm may legitimately run late, so its tolerance is longer.
    @Test
    fun `an inexact rung is overdue after fifteen minutes`() {
        val inexact = checkNotNull(correct().armed).copy(exactAllowed = false)
        val state = { late: Duration -> correct(armed = inexact, exactAllowedNow = false, late = late) }
        assertEquals(emptySet<RepairEvidence>(), evidence(state(10.minutes)))
        assertEquals(emptySet<RepairEvidence>(), evidence(state(WatchdogEvidence.INEXACT_TOLERANCE)))
        assertEquals(
            setOf(RepairEvidence.RUNG_OVERDUE),
            evidence(state(WatchdogEvidence.INEXACT_TOLERANCE + 1.milliseconds)),
        )
    }

    // What was armed decides the tolerance, not what is allowed now: an inexact alarm armed before exact
    // capability returned still gets the long tolerance (the capability change is evidence of its own).
    @Test
    fun `the tolerance follows what was armed`() {
        val inexact = checkNotNull(correct().armed).copy(exactAllowed = false)
        val found = evidence(correct(armed = inexact, exactAllowedNow = true, late = 10.minutes))
        assertEquals(setOf(RepairEvidence.EXACT_CAPABILITY_CHANGED), found)
    }

    @Test
    fun `several signals are all reported`() {
        val found = evidence(correct(armed = null, alarmPresent = false, late = 5.minutes))
        assertEquals(
            setOf(RepairEvidence.RECORD_MISSING, RepairEvidence.ALARM_ABSENT, RepairEvidence.RUNG_OVERDUE),
            found,
        )
    }

    @Test
    fun `the tolerances are inside the catch up window`() {
        assertTrue(WatchdogEvidence.EXACT_TOLERANCE < WatchdogEvidence.INEXACT_TOLERANCE)
        assertTrue(WatchdogEvidence.INEXACT_TOLERANCE < com.momtime.shared.engine.CatchUp.WINDOW)
    }
}
