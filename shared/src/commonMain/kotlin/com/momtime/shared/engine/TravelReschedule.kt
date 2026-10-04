package com.momtime.shared.engine

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * Where an open occurrence goes when the device's zone changes (golden scenario 8, ADR 0068). Pure: it reads no
 * clock and no zone, both are arguments (invariants 6 and 8).
 */
object TravelReschedule {
    /**
     * The instant an open occurrence on [localDate] at [timeOfDay] is due after the zone changed to [newZone] at
     * [changedAt]: that wall clock time in the new zone, but never before [changedAt]. Travel never makes an
     * occurrence overdue that was not: flying east, where the new zone's wall clock time has already passed, makes
     * it due at [changedAt] and not missed; flying west moves it later. An occurrence at [changedAt] is due now.
     */
    fun rescheduledInstant(
        localDate: LocalDate,
        timeOfDay: LocalTime,
        newZone: TimeZone,
        changedAt: Instant,
    ): Instant = maxOf(LocalDateTime(localDate, timeOfDay).toInstant(newZone), changedAt)
}
