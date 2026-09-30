package com.momtime.shared.engine

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Max 3 snoozes per occurrence, never past the next occurrence of the same template
 * (ARCHITECTURE.md section 4.5, golden scenario 4). `canSnooze` is the single source of truth
 * for the cap — call it both when handling a snooze request and when deriving the next alarm
 * to arm for a SNOOZED occurrence, so a fourth rung can never be offered from either path.
 */
object SnoozePolicy {
    const val MAX_SNOOZES = 3

    fun canSnooze(currentSnoozeCount: Int): Boolean = currentSnoozeCount < MAX_SNOOZES

    /**
     * The snoozed-until instant, clamped to strictly before the next occurrence of the same
     * template. Returns null if the cap is already reached — callers must not arm a snooze in
     * that case.
     */
    fun snoozedUntil(
        now: Instant,
        snoozeDuration: Duration,
        currentSnoozeCount: Int,
        nextOccurrenceOfSameTemplateInstant: Instant?,
    ): Instant? {
        if (!canSnooze(currentSnoozeCount)) return null
        val candidate = now + snoozeDuration
        return if (nextOccurrenceOfSameTemplateInstant != null && candidate >= nextOccurrenceOfSameTemplateInstant) {
            null
        } else {
            candidate
        }
    }
}
