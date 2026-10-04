package com.momtime.shared.engine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Where an open occurrence goes when the zone changes (golden scenario 8, ADR 0068): its wall clock time in the new
 * zone, but never before the instant of the change.
 */
class TravelRescheduleTest {
    private val date = LocalDate(2026, 1, 10)
    private val eight = LocalTime(8, 0)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val newYork = TimeZone.of("America/New_York")

    // 08:00 in Tokyo (UTC+9) is 23:00 UTC the day before; in New York (UTC-5 in January) it is 13:00 UTC.
    private val tokyoEight = Instant.parse("2026-01-09T23:00:00Z")
    private val newYorkEight = Instant.parse("2026-01-10T13:00:00Z")

    @Test
    fun `flying east where the wall clock time has passed makes the occurrence due at the change, not earlier`() {
        val changedAt = Instant.parse("2026-01-10T01:30:00Z")

        assertEquals(changedAt, TravelReschedule.rescheduledInstant(date, eight, tokyo, changedAt))
    }

    @Test
    fun `flying west moves the occurrence later, to its wall clock time`() {
        val changedAt = Instant.parse("2026-01-10T01:30:00Z")

        assertEquals(newYorkEight, TravelReschedule.rescheduledInstant(date, eight, newYork, changedAt))
    }

    // East, but the new wall clock time is still ahead of the change: it simply moves there.
    @Test
    fun `an occurrence whose new wall clock time is still ahead goes there`() {
        val changedAt = Instant.parse("2026-01-09T20:00:00Z")

        assertEquals(tokyoEight, TravelReschedule.rescheduledInstant(date, eight, tokyo, changedAt))
    }

    @Test
    fun `at the change itself the occurrence is due now`() {
        assertEquals(tokyoEight, TravelReschedule.rescheduledInstant(date, eight, tokyo, tokyoEight))
        assertEquals(
            tokyoEight + kotlin.time.Duration.parse("1s"),
            TravelReschedule.rescheduledInstant(date, eight, tokyo, tokyoEight + kotlin.time.Duration.parse("1s")),
        )
    }
}
