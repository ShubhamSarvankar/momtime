package com.momtime.shared.engine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The catch up window (ADR 0056, ARCHITECTURE.md section 4.5 and 5.3). A rung that fires late is delivered
 * as it would have been (a ring, or silent under quiet hours or the budget) only while it is within
 * [WINDOW] of its own instant. Beyond it the rung is still delivered, but as a silent notice: no ring and
 * no heads up, so that nothing sounds hours late and nothing is dropped either. The window decides how a
 * late rung is presented and never whether it is delivered.
 *
 * Only the fire path asks. The watchdog and boot make no catch up decision: they arm the earliest rung that
 * has not fired, for now, and the fire path decides when it fires. An occurrence beyond its grace is
 * `MISSED` by `Reconcile` (ADR 0030) and has no rung to deliver at all.
 *
 * Pure: it reads no clock (invariant 8) and schedules nothing (invariant 6).
 */
object CatchUp {
    /** How late a rung may be and still be presented normally. The boundary is inclusive. */
    val WINDOW: Duration = 30.minutes

    /** True if a rung due at [rung] is not yet due at [at], or is overdue by no more than [WINDOW]. */
    fun isWithinWindow(
        rung: Instant,
        at: Instant,
    ): Boolean = at - rung <= WINDOW
}
