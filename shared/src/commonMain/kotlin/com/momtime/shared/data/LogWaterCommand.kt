package com.momtime.shared.data

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The only writer of `WATER_LOGGED` (ADR 0083 item 1, invariant 3). The water screen, the widget and the tile all
 * dispatch it. One call logs one glass, [GLASS_ML] millilitres: a constant, not a setting, in v1. The event records
 * the instant and the zone she logged it in (ADR 0086, invariant 9), has source `USER`, belongs to no occurrence, and
 * is the only thing written. It reads no occurrence and arms nothing: water is not adherence.
 *
 * [dispatch] takes `now` from the caller's injected clock and the device's current zone, which it never reads itself.
 */
class LogWaterCommand(
    private val events: EventRepository,
    private val newId: () -> String,
) {
    fun dispatch(
        now: Instant,
        zone: TimeZone,
    ) {
        events.insert(
            Event(
                id = newId(),
                occurrenceId = null,
                eventType = EventType.WATER_LOGGED,
                deviceTimestamp = now,
                effectiveAt = null,
                source = EventSource.USER,
                payload = EventPayload.Water(GLASS_ML, zone),
            ),
        )
    }

    companion object {
        const val GLASS_ML = 250
    }
}
