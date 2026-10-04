package com.momtime.shared.engine

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import kotlin.time.Instant

/**
 * The events the alarm path appends. Neither changes an occurrence's state (invariant 3): an alarm being
 * armed or fired is a fact about delivery, recorded for the log, and the occurrence stays as it was. The
 * shape is decided here, once, so a platform cannot give it a different one. Both carry no payload:
 * nothing about how the device delivered it belongs in the log (invariant 5, ADR 0048).
 */
object AlarmEvents {
    /** A rung was armed for the first time, or the rung expected next changed (never a refresh). */
    fun scheduled(
        id: String,
        occurrenceId: String,
        now: Instant,
    ): Event = systemEvent(id, occurrenceId, EventType.ALARM_SCHEDULED, now)

    /** The expected rung's alarm fired. The count of these is the record of rungs that have fired. */
    fun fired(
        id: String,
        occurrenceId: String,
        now: Instant,
    ): Event = systemEvent(id, occurrenceId, EventType.ALARM_FIRED, now)

    private fun systemEvent(
        id: String,
        occurrenceId: String,
        type: EventType,
        now: Instant,
    ) = Event(
        id = id,
        occurrenceId = occurrenceId,
        eventType = type,
        deviceTimestamp = now,
        effectiveAt = null,
        source = EventSource.SYSTEM,
        payload = EventPayload.None,
    )
}
