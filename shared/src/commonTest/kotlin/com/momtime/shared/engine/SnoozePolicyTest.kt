package com.momtime.shared.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SnoozePolicyTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    // Golden scenario 4: snoozing past the next occurrence of the same template clamps rather
    // than producing two simultaneous PENDING occurrences.
    @Test
    fun `snooze past the next occurrence of the same template is refused, not clamped into overlap`() {
        val nextOccurrence = now + 5.minutes
        val result =
            SnoozePolicy.snoozedUntil(
                now = now,
                snoozeDuration = 10.minutes,
                currentSnoozeCount = 0,
                nextOccurrenceOfSameTemplateInstant = nextOccurrence,
            )
        assertNull(result)
    }

    @Test
    fun `snooze well before the next occurrence succeeds`() {
        val nextOccurrence = now + 60.minutes
        val result =
            SnoozePolicy.snoozedUntil(
                now = now,
                snoozeDuration = 10.minutes,
                currentSnoozeCount = 0,
                nextOccurrenceOfSameTemplateInstant = nextOccurrence,
            )
        assertEquals(now + 10.minutes, result)
    }

    // Golden scenario 4: the third snooze (at the cap) forces a terminal outcome rather than
    // silently allowing a fourth.
    @Test
    fun `third snooze is allowed, fourth is refused`() {
        assertEquals(true, SnoozePolicy.canSnooze(currentSnoozeCount = 2))
        assertEquals(false, SnoozePolicy.canSnooze(currentSnoozeCount = 3))

        val thirdSnooze =
            SnoozePolicy.snoozedUntil(
                now,
                10.minutes,
                currentSnoozeCount = 2,
                nextOccurrenceOfSameTemplateInstant = null,
            )
        val fourthSnooze =
            SnoozePolicy.snoozedUntil(
                now,
                10.minutes,
                currentSnoozeCount = 3,
                nextOccurrenceOfSameTemplateInstant = null,
            )

        assertEquals(now + 10.minutes, thirdSnooze)
        assertNull(fourthSnooze)
    }
}
