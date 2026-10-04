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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ArmingSelectionTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val device = setOf(Channel.RING, Channel.RING_REPEAT)

    /** [fired] rungs have fired, each on time, so the record is the instants of the first [fired] rungs. */
    private fun candidate(
        id: String,
        slot: Int,
        scheduled: Instant,
        criticality: Criticality,
        fired: Int = 0,
    ) = ArmCandidate(
        Occurrence(id, "tmpl-$id", LocalDate(2023, 11, 14), scheduled, TimeZone.UTC, OccurrenceState.PENDING, slot),
        criticality,
        EscalationLadder
            .forOccurrence(scheduled, criticality)
            .filter { it.channel in device }
            .take(fired)
            .map { it.instant },
    )

    @Test
    fun `no candidates selects nothing`() {
        assertNull(ArmingSelection.next(emptyList(), device, t0))
    }

    @Test
    fun `the selection carries the slot of the occurrence the rung belongs to`() {
        val selection = ArmingSelection.next(listOf(candidate("a", 7, t0, Criticality.STANDARD)), device, t0)
        assertEquals(RungSelection("a", 7, EscalationRung(t0, Channel.RING)), selection)
    }

    // The record of fired rungs decides what is next. Time decides only which unfired rungs are too late.
    @Test
    fun `selection follows the fired record`() {
        val c = candidate("a", 1, t0, Criticality.STANDARD, fired = 1)
        assertEquals(t0 + 10.minutes, ArmingSelection.next(listOf(c), device, t0)?.rung?.instant)
        assertNull(ArmingSelection.next(listOf(candidate("a", 1, t0, Criticality.STANDARD, fired = 2)), device, t0))
    }

    // A caregiver rung of A (10 minutes in) comes before a ring rung of B (12 minutes in).
    @Test
    fun `a caregiver rung of one occurrence does not precede a ring rung of another`() {
        val a = candidate("a", 1, t0, Criticality.CRITICAL, fired = 2)
        val b = candidate("b", 2, t0 + 12.minutes, Criticality.STANDARD)
        val selection = checkNotNull(ArmingSelection.next(listOf(a, b), device, t0))
        assertEquals("b", selection.occurrenceId)
        assertEquals(2, selection.alarmSlot)
    }

    @Test
    fun `expected rung is the first unfired rung of the channels, or null`() {
        val c = candidate("a", 1, t0, Criticality.CRITICAL)
        assertEquals(t0, ArmingSelection.expectedFor(c, device, t0)?.instant)
        assertEquals(
            t0 + 5.minutes,
            ArmingSelection.expectedFor(candidate("a", 1, t0, Criticality.CRITICAL, fired = 1), device, t0)?.instant,
        )
        assertNull(
            ArmingSelection.expectedFor(candidate("a", 1, t0, Criticality.CRITICAL, fired = 2), device, t0),
        )
    }

    @Test
    fun `ties between occurrences are broken the same way in either order`() {
        val a = candidate("a", 1, t0, Criticality.GENTLE)
        val b = candidate("b", 2, t0, Criticality.GENTLE)
        assertEquals("a", ArmingSelection.next(listOf(a, b), device, t0)?.occurrenceId)
        assertEquals("a", ArmingSelection.next(listOf(b, a), device, t0)?.occurrenceId)
    }

    // The catch up rule applies to selection (ADR 0056): a rung more than 30 minutes overdue is skipped and
    // the next one that is ahead or within the window is chosen.
    @Test
    fun `a stale rung is skipped for the next one that is within the window`() {
        val c = candidate("a", 1, t0, Criticality.STANDARD)
        // RING at t0 is 35 minutes overdue, RING_REPEAT at t0 + 10 minutes is 25 minutes overdue.
        val selection = checkNotNull(ArmingSelection.next(listOf(c), device, t0 + 35.minutes))
        assertEquals(EscalationRung(t0 + 10.minutes, Channel.RING_REPEAT), selection.rung)
    }

    @Test
    fun `when every rung is stale nothing is selected and nothing is expected`() {
        val c = candidate("a", 1, t0, Criticality.STANDARD)
        assertNull(ArmingSelection.next(listOf(c), device, t0 + 1.hours))
        assertNull(ArmingSelection.expectedFor(c, device, t0 + 1.hours))
    }
}
