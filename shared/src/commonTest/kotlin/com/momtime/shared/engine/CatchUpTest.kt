package com.momtime.shared.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CatchUpTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `the window is thirty minutes`() {
        assertEquals(30.minutes, CatchUp.WINDOW)
    }

    // The boundary is inclusive: exactly 30 minutes overdue is still presented normally, one millisecond more is not.
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
    fun `hours late is beyond the window`() {
        assertFalse(CatchUp.isWithinWindow(t0, t0 + 3.hours))
    }
}
