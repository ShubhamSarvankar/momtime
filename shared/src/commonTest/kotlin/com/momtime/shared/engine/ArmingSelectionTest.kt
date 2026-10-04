package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ArmingSelectionTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val device = setOf(Channel.RING, Channel.RING_REPEAT)

    private fun candidate(
        id: String,
        slot: Int,
        scheduled: Instant,
        criticality: Criticality,
        fired: Int = 0,
    ) = ArmCandidate(
        Occurrence(id, "tmpl-$id", LocalDate(2023, 11, 14), scheduled, TimeZone.UTC, OccurrenceState.PENDING, slot),
        criticality,
        fired,
    )

    @Test
    fun `no candidates selects nothing`() {
        assertNull(ArmingSelection.next(emptyList(), device))
    }

    @Test
    fun `the selection carries the slot of the occurrence the rung belongs to`() {
        val selection = ArmingSelection.next(listOf(candidate("a", 7, t0, Criticality.STANDARD)), device)
        assertEquals(RungSelection("a", 7, EscalationRung(t0, Channel.RING)), selection)
    }

    // The fire count decides what is next. Time is not an input.
    @Test
    fun `selection follows the fired count`() {
        val c = candidate("a", 1, t0, Criticality.STANDARD, fired = 1)
        assertEquals(t0 + 10.minutes, ArmingSelection.next(listOf(c), device)?.rung?.instant)
        assertNull(ArmingSelection.next(listOf(c.copy(firedCount = 2)), device))
    }

    // Golden scenario 2 through the entry point production calls: a reboot part way through a ladder resumes at the
    // rung after the ones that fired, never at the start of the ladder.
    @Test
    fun `scenario 2 a reboot part way through resumes at the next rung`() {
        val afterRing = candidate("a", 1, t0, Criticality.CRITICAL, fired = 1)
        assertEquals(
            EscalationRung(t0 + 5.minutes, Channel.RING_REPEAT),
            ArmingSelection.next(listOf(afterRing), device)?.rung,
        )
        val afterBoth = candidate("a", 1, t0, Criticality.CRITICAL, fired = 2)
        assertNull(
            ArmingSelection.next(listOf(afterBoth), device),
            "the rest of the ladder is the server's, not this device's",
        )
    }

    // A caregiver rung of A (10 minutes in) comes before a ring rung of B (12 minutes in).
    @Test
    fun `a caregiver rung of one occurrence does not precede a ring rung of another`() {
        val a = candidate("a", 1, t0, Criticality.CRITICAL, fired = 2)
        val b = candidate("b", 2, t0 + 12.minutes, Criticality.STANDARD)
        val selection = checkNotNull(ArmingSelection.next(listOf(a, b), device))
        assertEquals("b", selection.occurrenceId)
        assertEquals(2, selection.alarmSlot)
    }

    @Test
    fun `expected rung is the first unfired rung of the channels, or null`() {
        val c = candidate("a", 1, t0, Criticality.CRITICAL)
        assertEquals(t0, ArmingSelection.expectedFor(c, device)?.instant)
        assertEquals(t0 + 5.minutes, ArmingSelection.expectedFor(c.copy(firedCount = 1), device)?.instant)
        assertNull(ArmingSelection.expectedFor(c.copy(firedCount = 2), device))
    }

    @Test
    fun `ties between occurrences are broken the same way in either order`() {
        val a = candidate("a", 1, t0, Criticality.GENTLE)
        val b = candidate("b", 2, t0, Criticality.GENTLE)
        assertEquals("a", ArmingSelection.next(listOf(a, b), device)?.occurrenceId)
        assertEquals("a", ArmingSelection.next(listOf(b, a), device)?.occurrenceId)
    }

    // A running snooze hides the ladder: the only thing armed for the occurrence is the snooze's end, even though
    // the next rung (5 minutes in) falls inside the snooze.
    @Test
    fun `a running snooze is the only thing armed for its occurrence`() {
        val snoozed = candidate("a", 1, t0, Criticality.CRITICAL, fired = 1).copy(snoozeEnd = t0 + 11.minutes)
        val selection = checkNotNull(ArmingSelection.next(listOf(snoozed), device))
        assertEquals(EscalationRung(t0 + 11.minutes, Channel.RING), selection.rung)
        assertEquals(true, selection.snoozeWake)
        assertEquals(EscalationRung(t0 + 11.minutes, Channel.RING), ArmingSelection.expectedFor(snoozed, device))
    }

    // The snooze consumed no rung: once it has ended the same count of fired rungs gives the same next rung, which
    // is now overdue and armed for now by the caller.
    @Test
    fun `after the snooze has ended the next rung is the one the count says`() {
        val ended = candidate("a", 1, t0, Criticality.CRITICAL, fired = 1)
        val selection = checkNotNull(ArmingSelection.next(listOf(ended), device))
        assertEquals(EscalationRung(t0 + 5.minutes, Channel.RING_REPEAT), selection.rung)
        assertEquals(false, selection.snoozeWake)
    }

    // The snooze of one occurrence does not hold back another occurrence's rung that is due earlier.
    @Test
    fun `a snooze does not hold back an earlier rung of another occurrence`() {
        val snoozed = candidate("a", 1, t0, Criticality.STANDARD, fired = 1).copy(snoozeEnd = t0 + 20.minutes)
        val other = candidate("b", 2, t0 + 3.minutes, Criticality.STANDARD)
        assertEquals("b", ArmingSelection.next(listOf(snoozed, other), device)?.occurrenceId)
    }

    @Test
    fun `a caller that does not deliver RING has no snooze end to arm`() {
        val snoozed = candidate("a", 1, t0, Criticality.STANDARD).copy(snoozeEnd = t0 + 20.minutes)
        assertNull(ArmingSelection.next(listOf(snoozed), setOf(Channel.RING_REPEAT)))
    }
}
