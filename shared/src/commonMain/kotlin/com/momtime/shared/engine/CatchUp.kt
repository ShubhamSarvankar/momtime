package com.momtime.shared.engine

import com.momtime.shared.domain.EscalationRung
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The one catch up rule (ADR 0056, ARCHITECTURE.md section 4.5 and 5.3). A rung that is found overdue rings
 * late only while it is within [WINDOW] of its instant. Beyond that it does not ring: selection moves on to
 * the next rung that is still ahead or within the window, and if none remains the occurrence is derived to
 * `MISSED` at the end of its grace (`Reconcile`). Every caller that discovers a rung late, the watchdog and
 * boot alike, goes through this one function, because otherwise a watchdog delayed by Doze could ring a
 * reminder hours late, which is the outcome the boot rule exists to prevent.
 *
 * Pure: it reads no clock (invariant 8) and schedules nothing (invariant 6).
 */
object CatchUp {
    /** How late a rung may ring. The boundary is inclusive: a rung exactly [WINDOW] overdue still rings. */
    val WINDOW: Duration = 30.minutes

    /** True if a rung due at [rung] may still ring at [at]: not yet due, or overdue by no more than [WINDOW]. */
    fun isWithinWindow(
        rung: Instant,
        at: Instant,
    ): Boolean = at - rung <= WINDOW

    /**
     * The rungs of [rungs] (one occurrence's ladder, already limited to the channels the caller delivers) that
     * can still be armed at [now], given when its rungs fired ([firedAt], the `deviceTimestamp` of each
     * `ALARM_FIRED` event, oldest first).
     *
     * Each fire consumed the first rung that was still within the window when it fired, and rungs before it
     * that were not were skipped without ringing. This is a function of the log and of [now]; a fired rung
     * never comes back, whatever [now] is (golden scenario 14). [now] decides only which rungs that have not
     * fired are too late.
     */
    fun remaining(
        rungs: List<EscalationRung>,
        firedAt: List<Instant>,
        now: Instant,
    ): List<EscalationRung> {
        var next = 0
        for (fired in firedAt) {
            while (next < rungs.size && !isWithinWindow(rungs[next].instant, fired)) next++
            next++
        }
        while (next < rungs.size && !isWithinWindow(rungs[next].instant, now)) next++
        return rungs.drop(next)
    }
}
