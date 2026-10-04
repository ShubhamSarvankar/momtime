package com.momtime.shared.domain

import kotlin.time.Instant

/**
 * The one thing a platform must do for the alarm subsystem: have exactly one alarm fire for a slot at an
 * instant. It deals in slots and instants and nothing else. Which mechanism realises it, and what the
 * platform can do, are the implementation's business and appear nowhere in this interface (invariants 5
 * and 6, ADR 0019).
 *
 * [slot] is an occurrence's `alarmSlot` (ARCHITECTURE.md section 5.4).
 */
interface AlarmScheduler {
    /**
     * Arms an alarm for [slot] at [at], replacing whatever is armed for the same slot. An [at] that has
     * already passed means "as soon as possible"; it is never an error and never a negative delay.
     */
    fun arm(
        slot: Int,
        at: Instant,
    )

    /** Removes the alarm armed for [slot]. A slot with nothing armed is not an error. */
    fun cancel(slot: Int)
}
