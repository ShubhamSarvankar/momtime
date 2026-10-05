package com.momtime.shared.engine

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.OccurrenceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The rules of her three actions and the shape of the events they write (ADR 0066). */
class OccurrenceActionsTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val ten = 10.minutes
    private val all = setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SNOOZE, OccurrenceAction.SKIP)
    private val noSnooze = setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SKIP)

    private fun available(
        state: OccurrenceState = OccurrenceState.PENDING,
        snoozed: Int = 0,
        next: Instant? = null,
    ) = OccurrenceActions.available(state, snoozed, now, ten, next)

    @Test
    fun `a pending or snoozed occurrence offers all three`() {
        assertEquals(all, available(OccurrenceState.PENDING))
        assertEquals(all, available(OccurrenceState.SNOOZED, snoozed = 1))
    }

    // WITHDRAWN is terminal (ADR 0079): the definition is isTerminal, in one place, and not a list kept here.
    @Test
    fun `a withdrawn occurrence offers no action`() {
        assertEquals(emptySet(), available(OccurrenceState.WITHDRAWN))
        assertEquals(emptySet(), available(OccurrenceState.WITHDRAWN, snoozed = 1, next = now + 5.minutes))
    }

    @Test
    fun `a terminal occurrence offers nothing`() {
        for (state in listOf(OccurrenceState.COMPLETED, OccurrenceState.SKIPPED, OccurrenceState.MISSED)) {
            assertEquals(emptySet(), available(state), "$state")
        }
    }

    // Golden scenario 4 and the cap: the third snooze is the last.
    @Test
    fun `the fourth snooze is not offered`() {
        assertEquals(all, available(snoozed = 2))
        assertEquals(noSnooze, available(snoozed = SnoozePolicy.MAX_SNOOZES))
    }

    // A snooze that would end at or after the next occurrence of the same template is not offered; one that ends
    // before it is.
    @Test
    fun `a snooze that would reach the next occurrence is not offered`() {
        assertEquals(noSnooze, available(next = now + ten))
        assertEquals(noSnooze, available(next = now + 5.minutes))
        assertEquals(all, available(next = now + ten + 1.minutes))
    }

    @Test
    fun `a snooze is running only while the snoozes that ended are fewer than the snoozes taken`() {
        val at = now - 3.minutes
        val end = at + ten
        assertEquals(end, OccurrenceActions.runningSnoozeEnd(OccurrenceState.SNOOZED, end, 1, 0))
        assertNull(OccurrenceActions.runningSnoozeEnd(OccurrenceState.SNOOZED, end, 1, 1), "it has ended")
        assertEquals(end, OccurrenceActions.runningSnoozeEnd(OccurrenceState.SNOOZED, end, 2, 1))
        assertNull(OccurrenceActions.runningSnoozeEnd(OccurrenceState.PENDING, null, 0, 0), "never snoozed")
        assertNull(OccurrenceActions.runningSnoozeEnd(OccurrenceState.PENDING, end, 1, 0), "not SNOOZED")
        assertNull(OccurrenceActions.runningSnoozeEnd(OccurrenceState.SNOOZED, null, 1, 0), "no snooze event")
    }

    @Test
    fun `her three actions are user events and the snooze carries its number`() {
        // The acknowledgement carries the tags it was given (ADR 0086): schema version 6 changed this expectation
        // from no payload, authorised by Claude (technical review).
        val tags = setOf(NutritionTag.IRON, NutritionTag.DAIRY)
        assertEquals(
            Event("e1", "occ", EventType.COMPLETED, now, null, EventSource.USER, EventPayload.Completion(tags)),
            OccurrenceActions.acknowledged("e1", "occ", now, tags),
        )
        assertEquals(
            Event("e2", "occ", EventType.SKIPPED, now, null, EventSource.USER, EventPayload.None),
            OccurrenceActions.skipped("e2", "occ", now),
        )
        assertEquals(
            Event("e3", "occ", EventType.SNOOZED, now, null, EventSource.USER, EventPayload.Snooze(2, now + ten)),
            OccurrenceActions.snoozed("e3", "occ", now, snoozeNumber = 2, snoozedUntil = now + ten),
        )
    }
}
