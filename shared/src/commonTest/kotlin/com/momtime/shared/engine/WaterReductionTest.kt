package com.momtime.shared.engine

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** The water reduction (ADR 0086 item 5): pure, over events, nothing read from a clock or a device. */
class WaterReductionTest {
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val newYork = TimeZone.of("America/New_York")
    private val farFuture = Instant.parse("2100-01-01T00:00:00Z")
    private var seq = 0

    private fun glass(
        at: Instant,
        zone: TimeZone?,
        ml: Int = 250,
        type: EventType = EventType.WATER_LOGGED,
        payload: EventPayload = EventPayload.Water(ml, zone),
    ) = Event(
        id = "evt-${seq++}",
        occurrenceId = null,
        eventType = type,
        deviceTimestamp = at,
        effectiveAt = null,
        source = EventSource.USER,
        payload = payload,
    )

    // 10 June 2026 is in daylight time in New York (UTC-4), which the 15:30 below depends on; it is asserted.
    @Test
    fun `a glass belongs to the day it was drunk in the zone it was logged in`() {
        val day = LocalDate(2026, 6, 10)
        val first = LocalDateTime(2026, 6, 10, 2, 0).toInstant(kolkata)
        val second = LocalDateTime(2026, 6, 11, 1, 0).toInstant(kolkata)
        assertEquals(
            LocalDateTime(2026, 6, 9, 16, 30),
            first.toLocalDateTime(newYork),
            "glass 1 is day D-1 in New York",
        )
        assertEquals(
            LocalDateTime(2026, 6, 10, 15, 30),
            second.toLocalDateTime(newYork),
            "glass 2 is 15:30 on D in New York",
        )
        val events = listOf(glass(first, kolkata), glass(second, newYork))
        // The fallback zone is passed as each of the two zones and as a third: no zone passed by the caller can
        // reproduce {D: 500} for rows that carry their own, which is the only way to get it.
        for (fallback in listOf(kolkata, newYork, TimeZone.UTC)) {
            assertEquals(mapOf(day to 500), WaterReduction.totals(events, farFuture, fallback), "fallback $fallback")
        }
    }

    @Test
    fun `a row with no zone is attributed in the fallback zone and only such a row is`() {
        val auckland = TimeZone.of("Pacific/Auckland")
        val beforeMidnight = LocalDateTime(2026, 6, 10, 23, 50).toInstant(kolkata)
        val afterMidnight = LocalDateTime(2026, 6, 11, 0, 10).toInstant(kolkata)
        // The same two instants with a zone of their own: both are 11 June in Auckland (UTC+12), while the fallback
        // zone has them either side of its midnight.
        val events =
            listOf(
                glass(beforeMidnight, null, ml = 100),
                glass(afterMidnight, null, ml = 200),
                glass(beforeMidnight, auckland, ml = 400),
                glass(afterMidnight, auckland, ml = 800),
            )
        assertEquals(
            mapOf(LocalDate(2026, 6, 10) to 100, LocalDate(2026, 6, 11) to 200 + 400 + 800),
            WaterReduction.totals(events, farFuture, kolkata),
        )
        // The fallback decides the rows with no zone and nothing else: in UTC the null rows are both 10 June (18:20Z
        // and 18:40Z), the Auckland rows still 11 June.
        assertEquals(
            mapOf(LocalDate(2026, 6, 10) to 100 + 200, LocalDate(2026, 6, 11) to 400 + 800),
            WaterReduction.totals(events, farFuture, TimeZone.UTC),
        )
    }

    @Test
    fun `asOf is inclusive and nothing after it counts`() {
        val asOf = LocalDateTime(2026, 6, 10, 12, 0).toInstant(kolkata)
        val events =
            listOf(
                glass(asOf - 1.milliseconds, kolkata, ml = 100),
                glass(asOf, kolkata, ml = 200),
                // The step that would produce a count after asOf if the bound were wrong: this event exists.
                glass(asOf + 1.milliseconds, kolkata, ml = 400),
            )
        assertEquals(mapOf(LocalDate(2026, 6, 10) to 300), WaterReduction.totals(events, asOf, kolkata))
    }

    // Not in the plan; for review. An event that is not a water log, or a water log with no water payload, adds nothing
    // (neither can be written through the repository, whose column guard refuses the first; the reduction is pure).
    @Test
    fun `an event that is not a water log counts nothing, with a water log beside it`() {
        val at = LocalDateTime(2026, 6, 10, 12, 0).toInstant(kolkata)
        val events =
            listOf(
                glass(at, kolkata, ml = 100),
                glass(at, kolkata, ml = 4000, type = EventType.WEIGHT_LOGGED),
                glass(at, kolkata, type = EventType.WATER_LOGGED, payload = EventPayload.None),
            )
        assertEquals(mapOf(LocalDate(2026, 6, 10) to 100), WaterReduction.totals(events, farFuture, kolkata))
    }
}
