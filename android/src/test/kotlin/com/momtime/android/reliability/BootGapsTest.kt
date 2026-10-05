package com.momtime.android.reliability

import com.momtime.android.arming.t0
import com.momtime.android.store.BootInstant
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Boots the app did not run in (ADR 0071), from the boot counts it recorded. */
class BootGapsTest {
    private fun boot(
        count: Long,
        at: kotlin.time.Instant = t0,
    ) = BootInstant(count, at)

    @Test
    fun `the first record has no predecessor and so no gap, whatever its count`() {
        val first = boot(40)
        assertEquals(0, BootGaps.unseenBefore(listOf(first), first))
    }

    @Test
    fun `consecutive counts are no gap`() {
        val boots = listOf(boot(7), boot(8), boot(9))
        assertEquals(listOf(0, 0, 0), boots.map { BootGaps.unseenBefore(boots, it) })
    }

    @Test
    fun `a jump of two is one unseen boot and a jump of three is two`() {
        val boots = listOf(boot(7), boot(9), boot(12))
        assertEquals(listOf(0, 1, 2), boots.map { BootGaps.unseenBefore(boots, it) })
    }

    @Test
    fun `the order the records are given in does not matter`() {
        val boots = listOf(boot(12), boot(7), boot(9))
        assertEquals(listOf(2, 0, 1), boots.map { BootGaps.unseenBefore(boots, it) })
    }

    @Test
    fun `unseen boots are counted by the boot that ended the gap, at or after the start of the window`() {
        val from = t0
        val boots =
            listOf(
                boot(3, t0 - 20.days),
                boot(6, t0 - 1.hours), // a gap of two, but it ended before the window
                boot(8, t0), // a gap of one, ending exactly at the start of the window: counted
                boot(11, t0 + 1.days), // a gap of two, inside the window
            )

        assertEquals(3, BootGaps.unseenSince(boots, from))
        assertEquals(
            "one millisecond later the boot at the start is outside",
            2,
            BootGaps.unseenSince(
                boots,
                from + kotlin.time.Duration.parse("1ms"),
            ),
        )
    }

    @Test
    fun `no records means no gaps`() {
        assertEquals(0, BootGaps.unseenSince(emptyList(), t0))
    }
}
