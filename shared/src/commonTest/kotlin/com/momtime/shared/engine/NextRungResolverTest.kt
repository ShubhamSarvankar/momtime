package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EscalationRung
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NextRungResolverTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val every = Channel.entries.toSet()

    // The channels a device delivers (decision 14). The engine is told the set; it does not know it.
    private val device = setOf(Channel.RING, Channel.RING_REPEAT)

    // Golden scenario 2: reboot after RING and RING_REPEAT have fired but before CAREGIVER_INFO
    // restores exactly the remaining rungs, not the full original ladder.
    @Test
    fun `remaining after two rungs fired is exactly the tail of the ladder`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.CRITICAL)
        val remaining = NextRungResolver.remaining(ladder, firedCount = 2, channels = every)
        assertEquals(listOf(Channel.CAREGIVER_INFO, Channel.CAREGIVER_URGENT), remaining.map { it.channel })
    }

    // Golden scenario 18: app force-stopped mid-ladder — the watchdog's next pass must resume
    // from the correct remaining rung, not restart the ladder or lose it.
    @Test
    fun `remaining after zero rungs fired is the full ladder`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.STANDARD)
        val remaining = NextRungResolver.remaining(ladder, firedCount = 0, channels = every)
        assertEquals(ladder, remaining)
    }

    @Test
    fun `remaining after every rung fired is empty`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.GENTLE)
        val remaining = NextRungResolver.remaining(ladder, firedCount = ladder.size, channels = every)
        assertEquals(emptyList(), remaining)
    }

    // Golden scenario 15: two occurrences' ladders interleave — the one-alarm-at-a-time
    // selection always arms the chronologically next rung across all occurrences, not just the
    // next rung of whichever occurrence is currently being processed.
    @Test
    fun `global next rung picks the chronologically earliest across occurrences, not per-occurrence order`() {
        val occurrenceA = EscalationLadder.forOccurrence(t0, Criticality.CRITICAL) // t0, t0+5m, t0+10m, t0+20m
        val occurrenceB = EscalationLadder.forOccurrence(t0 + 2.minutes, Criticality.STANDARD) // t0+2m, t0+12m

        val next =
            NextRungResolver.globalNext(
                listOf(
                    NextRungResolver.PendingLadder("occ-A", occurrenceA),
                    NextRungResolver.PendingLadder("occ-B", occurrenceB),
                ),
                every,
            )

        checkNotNull(next)
        val (occurrenceId, rung) = next
        assertEquals("occ-A", occurrenceId)
        assertEquals(t0, rung.instant)

        // Once occ-A's t0 rung is consumed, occ-B's t0+2m rung becomes the global next, ahead
        // of occ-A's own t0+5m rung — proving the selection is genuinely cross-occurrence.
        val afterFirst =
            NextRungResolver.globalNext(
                listOf(
                    NextRungResolver.PendingLadder("occ-A", NextRungResolver.remaining(occurrenceA, 1, every)),
                    NextRungResolver.PendingLadder("occ-B", occurrenceB),
                ),
                every,
            )
        checkNotNull(afterFirst)
        assertEquals("occ-B", afterFirst.first)
        assertEquals(t0 + 2.minutes, afterFirst.second.instant)
    }

    @Test
    fun `no pending ladders means no next rung`() {
        assertNull(NextRungResolver.globalNext(emptyList(), every))
    }

    // A caregiver rung of one occurrence that comes before a ring rung of another must not be selected, or
    // it would delay the ring (ADR 0017, decision 14).
    @Test
    fun `a caregiver rung of one occurrence never precedes a ring rung of another`() {
        val a = EscalationLadder.forOccurrence(t0, Criticality.CRITICAL) // ring t0, repeat +5, caregiver +10, +20
        val b = EscalationLadder.forOccurrence(t0 + 12.minutes, Criticality.STANDARD)
        // A's two device rungs have fired. Its caregiver rung (+10) is still in the ladder, ahead of B's ring (+12).
        val pending =
            listOf(
                NextRungResolver.PendingLadder("occ-A", a.drop(2)),
                NextRungResolver.PendingLadder("occ-B", b),
            )
        val unfiltered = checkNotNull(NextRungResolver.globalNext(pending, every))
        assertEquals("occ-A" to Channel.CAREGIVER_INFO, unfiltered.first to unfiltered.second.channel)

        val next = checkNotNull(NextRungResolver.globalNext(pending, device))
        assertEquals("occ-B", next.first)
        assertEquals(Channel.RING, next.second.channel)
    }

    // Rungs of a channel the caller does not deliver are not counted among the fired: two fires of a
    // CRITICAL ladder leave nothing for the device, and the caregiver rungs do not shift the count.
    @Test
    fun `remaining filters by channel before counting fired rungs`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.CRITICAL)
        assertEquals(emptyList(), NextRungResolver.remaining(ladder, 2, device))
        assertEquals(listOf(Channel.RING_REPEAT), NextRungResolver.remaining(ladder, 1, device).map { it.channel })
        val caregiver = setOf(Channel.CAREGIVER_INFO, Channel.CAREGIVER_URGENT)
        assertEquals(caregiver, NextRungResolver.remaining(ladder, 0, caregiver).map { it.channel }.toSet())
    }

    // Two rungs at the same instant are ordered by occurrence id whatever order they arrive in. When the
    // first has fired the second is next, at an instant that has already passed.
    @Test
    fun `rungs at the same instant are chosen by occurrence id, not by input order`() {
        val ring = listOf(EscalationRung(t0, Channel.RING))
        val forward =
            listOf(NextRungResolver.PendingLadder("occ-A", ring), NextRungResolver.PendingLadder("occ-B", ring))
        assertEquals("occ-A", NextRungResolver.globalNext(forward, device)?.first)
        assertEquals("occ-A", NextRungResolver.globalNext(forward.reversed(), device)?.first)
        val afterA =
            listOf(NextRungResolver.PendingLadder("occ-A", emptyList()), NextRungResolver.PendingLadder("occ-B", ring))
        assertEquals("occ-B", NextRungResolver.globalNext(afterA, device)?.first)
    }
}
