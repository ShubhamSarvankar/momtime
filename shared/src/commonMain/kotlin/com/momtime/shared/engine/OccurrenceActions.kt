package com.momtime.shared.engine

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.OccurrenceState
import kotlin.time.Duration
import kotlin.time.Instant

/** What she can do with an occurrence that is due: the three actions of the ring screen and of a notification. */
enum class OccurrenceAction { ACKNOWLEDGE, SNOOZE, SKIP }

/**
 * The rules of her three actions and the shape of the events they write, decided once here so that no platform
 * gives them another (invariant 3, invariant 5). Pure: it reads no clock and touches no store.
 *
 * Acknowledge writes `COMPLETED` and skip writes `SKIPPED`, each by the user and with no payload. Snooze writes
 * `SNOOZED` carrying its number (the first is 1). None of them is written by stopping a sound or by dismissing a
 * notification: dismissing the alert is not completion (ARCHITECTURE.md section 4.6).
 *
 * A snooze is over when its alarm fires, and that is recorded by `SNOOZE_ENDED` ([AlarmEvents.snoozeEnded]), a
 * system event of its own. It is not `ALARM_FIRED`: the count of those is the record of ladder rungs that have
 * fired, and a snooze is not a rung, so counting it would make the next real rung vanish.
 */
object OccurrenceActions {
    /**
     * The actions she may take now. A terminal occurrence has none. Acknowledge and skip are always available
     * to one that is not terminal. Snooze is available only if [SnoozePolicy] allows it: fewer than
     * [SnoozePolicy.MAX_SNOOZES] already, and the snooze would end before the next occurrence of the same
     * template, if there is one ([nextOccurrenceOfSameTemplate]). An action that is not available is not offered
     * and, if requested anyway, is refused (never silently ignored).
     */
    fun available(
        state: OccurrenceState,
        snoozeCount: Int,
        now: Instant,
        snoozeDuration: Duration,
        nextOccurrenceOfSameTemplate: Instant?,
    ): Set<OccurrenceAction> {
        if (state == OccurrenceState.COMPLETED || state == OccurrenceState.SKIPPED || state == OccurrenceState.MISSED) {
            return emptySet()
        }
        val canSnooze =
            SnoozePolicy.snoozedUntil(now, snoozeDuration, snoozeCount, nextOccurrenceOfSameTemplate) != null
        return if (canSnooze) {
            setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SNOOZE, OccurrenceAction.SKIP)
        } else {
            setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SKIP)
        }
    }

    /**
     * When the snooze that is still running ends, or null if none is. An occurrence has a running snooze only
     * while it is `SNOOZED` and fewer snoozes have ended than were taken: each snooze ends exactly once, so the
     * counts are the record, and no instant is compared with the time (golden scenario 14).
     */
    fun runningSnoozeEnd(
        state: OccurrenceState,
        lastSnoozedAt: Instant?,
        snoozedCount: Int,
        endedCount: Int,
        snoozeDuration: Duration,
    ): Instant? =
        if (state == OccurrenceState.SNOOZED && lastSnoozedAt != null && endedCount < snoozedCount) {
            lastSnoozedAt + snoozeDuration
        } else {
            null
        }

    fun acknowledged(
        id: String,
        occurrenceId: String,
        now: Instant,
    ): Event = userEvent(id, occurrenceId, EventType.COMPLETED, now, EventPayload.None)

    fun skipped(
        id: String,
        occurrenceId: String,
        now: Instant,
    ): Event = userEvent(id, occurrenceId, EventType.SKIPPED, now, EventPayload.None)

    fun snoozed(
        id: String,
        occurrenceId: String,
        now: Instant,
        snoozeNumber: Int,
    ): Event = userEvent(id, occurrenceId, EventType.SNOOZED, now, EventPayload.Snooze(snoozeNumber))

    private fun userEvent(
        id: String,
        occurrenceId: String,
        type: EventType,
        now: Instant,
        payload: EventPayload,
    ) = Event(
        id = id,
        occurrenceId = occurrenceId,
        eventType = type,
        deviceTimestamp = now,
        effectiveAt = null,
        source = EventSource.USER,
        payload = payload,
    )
}
