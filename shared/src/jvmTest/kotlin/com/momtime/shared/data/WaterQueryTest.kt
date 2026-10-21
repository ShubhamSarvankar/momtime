package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.Event
import com.momtime.shared.engine.WaterReduction
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * `EventRepository.findWaterInRange` and the reduction over what it returns (ADR 0086 item 5). Every glass is logged
 * through `LogWaterCommand`, so the zone on the event is the one the production command records. A query by dates
 * cannot know the zone a glass was logged in, so it returns every glass within 14 hours of the dates' days in UTC and
 * the reduction attributes each by its own zone; a glass the query loses is lost for good.
 */
class WaterQueryTest {
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val newYork = TimeZone.of("America/New_York")
    private val kiritimati = TimeZone.of("Pacific/Kiritimati") // UTC+14, the widest offset east
    private val pagoPago = TimeZone.of("Pacific/Pago_Pago") // UTC-11, the far west
    private val farFuture = Instant.parse("2100-01-01T00:00:00Z")
    private val day = LocalDate(2026, 6, 10)
    private lateinit var driver: SqlDriver
    private lateinit var events: EventRepository
    private lateinit var log: LogWaterCommand
    private var ids = 0

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        events = SqlDelightEventRepository(MomTimeDatabase(driver))
        log = LogWaterCommand(events) { "water-${ids++}" }
    }

    @AfterTest
    fun tearDown() = driver.close()

    /** One glass through the command, at [hour]:[minute] on [date] as the clock of [zone] reads it, logged in [zone]. */
    private fun glass(
        zone: TimeZone,
        date: LocalDate,
        hour: Int,
        minute: Int = 0,
    ) = log.dispatch(LocalDateTime(date, LocalTime(hour, minute)).toInstant(zone), zone)

    private fun totals(found: List<Event>) = WaterReduction.totals(found, farFuture, kolkata)

    // The fixture of `a glass belongs to the day it was drunk in the zone it was logged in`, through the command and
    // the query for the single date D = 10 June 2026. It is daylight time in New York on that day (UTC-4), so the
    // second glass, 01:00 on 11 June in Kolkata, is 15:30 on 10 June there, and its instant is 19:30 UTC. A query for D
    // whose range was Kolkata's day D (to 18:30 UTC on 10 June) would lose it.
    @Test
    fun `the travel fixture survives the query`() {
        val second = LocalDateTime(2026, 6, 11, 1, 0).toInstant(kolkata)
        assertEquals(LocalDateTime(2026, 6, 10, 15, 30), second.toLocalDateTime(newYork))
        glass(kolkata, day, 2, 0)
        log.dispatch(second, newYork)
        // Present in the log and nowhere near D: the query must not return everything.
        glass(kolkata, day.plus(10, DateTimeUnit.DAY), 12)

        assertEquals(mapOf(day to 500), totals(events.findWaterInRange(day, day)))
    }

    @Test
    fun `the query reaches the widest offsets at both ends`() {
        glass(kiritimati, day, 0, 30) // 10:30 UTC on 9 June: before D's UTC day, and D where she was
        glass(pagoPago, day, 23, 30) // 10:30 UTC on 11 June: after D's UTC day, and D where she was
        glass(kolkata, day, 12)

        val found = events.findWaterInRange(day, day)
        assertEquals(3, found.size, "the glass at each end of the offsets and the one between")
        assertEquals(mapOf(day to 750), totals(found))
    }

    @Test
    fun `the query returns nothing outside the widened range`() {
        glass(kolkata, day, 12)
        // Present in the log, and out of range: more than 14 hours beyond D's UTC day on each side.
        glass(TimeZone.UTC, day.minus(2, DateTimeUnit.DAY), 12)
        glass(TimeZone.UTC, day.plus(2, DateTimeUnit.DAY), 12)

        assertEquals(1, events.findWaterInRange(day, day).size, "only the glass of D")
        assertEquals(
            3,
            events.findWaterInRange(day.minus(2, DateTimeUnit.DAY), day.plus(2, DateTimeUnit.DAY)).size,
            "all three, once the range holds them: they are in the log",
        )
    }

    @Test
    fun `a range of dates includes the whole span of its last date`() {
        val last = day.plus(1, DateTimeUnit.DAY)
        glass(pagoPago, last, 23, 30) // 10:30 UTC on 12 June: the last date of the range, in its far western zone
        glass(kolkata, day, 12)

        assertEquals(mapOf(day to 250, last to 250), totals(events.findWaterInRange(day, last)))
    }
}
