package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class NextRungResolverTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    // Golden scenario 2: reboot after RING and RING_REPEAT have fired but before CAREGIVER_INFO
    // restores exactly the remaining rungs, not the full original ladder.
    @Test
    fun `remaining after two rungs fired is exactly the tail of the ladder`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.CRITICAL)
        val remaining = NextRungResolver.remaining(ladder, firedCount = 2)
        assertEquals(listOf(Channel.CAREGIVER_INFO, Channel.CAREGIVER_URGENT), remaining.map { it.channel })
    }

    // Golden scenario 18: app force-stopped mid-ladder — the watchdog's next pass must resume
    // from the correct remaining rung, not restart the ladder or lose it.
    @Test
    fun `remaining after zero rungs fired is the full ladder`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.STANDARD)
        val remaining = NextRungResolver.remaining(ladder, firedCount = 0)
        assertEquals(ladder, remaining)
    }

    @Test
    fun `remaining after every rung fired is empty`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.GENTLE)
        val remaining = NextRungResolver.remaining(ladder, firedCount = ladder.size)
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
                    NextRungResolver.PendingLadder("occ-A", NextRungResolver.remaining(occurrenceA, 1)),
                    NextRungResolver.PendingLadder("occ-B", occurrenceB),
                ),
            )
        checkNotNull(afterFirst)
        assertEquals("occ-B", afterFirst.first)
        assertEquals(t0 + 2.minutes, afterFirst.second.instant)
    }

    @Test
    fun `no pending ladders means no next rung`() {
        assertNull(NextRungResolver.globalNext(emptyList()))
    }
}
