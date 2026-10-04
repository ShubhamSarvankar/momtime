package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.EscalationRung
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CatchUpTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val ladder =
        listOf(
            EscalationRung(t0, Channel.RING),
            EscalationRung(t0 + 5.minutes, Channel.RING_REPEAT),
            EscalationRung(t0 + 20.minutes, Channel.RING_REPEAT),
        )

    @Test
    fun `the window is thirty minutes`() {
        assertEquals(30.minutes, CatchUp.WINDOW)
    }

    // The boundary is inclusive: exactly 30 minutes overdue still rings, one millisecond more does not.
    @Test
    fun `the boundary is inclusive`() {
        assertTrue(CatchUp.isWithinWindow(t0, t0 + 30.minutes))
        assertFalse(CatchUp.isWithinWindow(t0, t0 + 30.minutes + 1.milliseconds))
    }

    @Test
    fun `a rung not yet due is within the window`() {
        assertTrue(CatchUp.isWithinWindow(t0 + 1.minutes, t0))
        assertTrue(CatchUp.isWithinWindow(t0, t0 - 10.minutes))
    }

    @Test
    fun `nothing fired and nothing late leaves the whole ladder`() {
        assertEquals(ladder, CatchUp.remaining(ladder, emptyList(), t0))
    }

    @Test
    fun `rungs beyond the window are skipped and the rest remain`() {
        // At 31 minutes the first rung is 31 overdue and the second 26: only the first is stale.
        assertEquals(ladder.drop(1), CatchUp.remaining(ladder, emptyList(), t0 + 31.minutes))
        assertEquals(ladder.drop(3), CatchUp.remaining(ladder, emptyList(), t0 + 51.minutes))
    }

    // The window boundary decides selection, not only the predicate: exactly 30 minutes overdue still remains.
    @Test
    fun `a rung exactly at the boundary remains and one millisecond later it does not`() {
        assertEquals(ladder, CatchUp.remaining(ladder, emptyList(), t0 + 30.minutes))
        assertEquals(ladder.drop(1), CatchUp.remaining(ladder, emptyList(), t0 + 30.minutes + 1.milliseconds))
    }

    @Test
    fun `each fire consumes the rung it fired for`() {
        assertEquals(ladder.drop(1), CatchUp.remaining(ladder, listOf(t0 + 2.seconds), t0 + 3.seconds))
        assertEquals(
            ladder.drop(2),
            CatchUp.remaining(ladder, listOf(t0 + 2.seconds, t0 + 5.minutes), t0 + 6.minutes),
        )
    }

    // The first rung was too late when the fire happened, so the fire was for the second. The skipped first
    // does not come back, and the second is not offered again.
    @Test
    fun `a rung skipped before a fire stays skipped`() {
        val fired = t0 + 31.minutes
        assertEquals(listOf(ladder[2]), CatchUp.remaining(ladder, listOf(fired), fired + 1.seconds))
    }

    // Golden scenario 14: a fired rung never comes back, however far back the clock is set.
    @Test
    fun `a fired rung does not come back when now moves backward`() {
        val fired = listOf(t0 + 1.seconds)
        assertEquals(ladder.drop(1), CatchUp.remaining(ladder, fired, t0 - 3.hours))
    }

    @Test
    fun `more fires than rungs leaves nothing`() {
        assertEquals(emptyList(), CatchUp.remaining(ladder, List(5) { t0 }, t0))
    }
}
