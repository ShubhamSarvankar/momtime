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

    /**
     * A snooze's alarm fired: the snooze is over. It is not `ALARM_FIRED`, whose count is the record of ladder
     * rungs that have fired, so ending a snooze never consumes a rung. Each snooze ends exactly once, so the
     * count of these against the count of `SNOOZED` is the record of whether a snooze is still running. It changes
     * no state: the occurrence stays `SNOOZED` until she acts or its grace ends.
     */
    fun snoozeEnded(
        id: String,
        occurrenceId: String,
        now: Instant,
    ): Event = systemEvent(id, occurrenceId, EventType.SNOOZE_ENDED, now)

    /**
     * The watchdog found positive evidence that the armed alarm was lost and re armed it. Written only on that
     * evidence, never on a pass that found everything correct (golden scenario 17). It names the occurrence whose
     * rung was being armed, and changes no state.
     */
    fun watchdogRepair(
        id: String,
        occurrenceId: String,
        now: Instant,
    ): Event = systemEvent(id, occurrenceId, EventType.WATCHDOG_REPAIR, now)

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
