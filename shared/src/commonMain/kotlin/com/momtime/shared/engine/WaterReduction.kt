package com.momtime.shared.engine

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Millilitres of water per local date, a reduction over the log (invariant 4, ADR 0086). Each glass belongs to the
 * local date of its `deviceTimestamp` in the zone it was logged in, wherever she reads the total: a glass at 23:30 in
 * Mumbai is that Mumbai day for ever. A row from before schema version 6 has no zone; only such a row is attributed in
 * [fallbackZone], which the caller passes (the device's current zone), a best guess and used for nothing else. An event
 * counts if its `deviceTimestamp` is at or before `asOf`. "Today" on a screen is a date in a zone the caller chooses.
 */
object WaterReduction {
    fun totals(
        events: List<Event>,
        asOf: Instant,
        fallbackZone: TimeZone,
    ): Map<LocalDate, Int> {
        val totals = HashMap<LocalDate, Int>()
        for (event in events) {
            if (event.eventType != EventType.WATER_LOGGED) continue
            val water = event.payload as? EventPayload.Water ?: continue
            if (event.deviceTimestamp > asOf) continue
            val date = event.deviceTimestamp.toLocalDateTime(water.zone ?: fallbackZone).date
            totals[date] = (totals[date] ?: 0) + water.waterMl
        }
        return totals
    }
}
