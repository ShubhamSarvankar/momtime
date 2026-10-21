package com.momtime.shared.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** `LogWaterCommand` (ADR 0083 item 1, ADR 0086 item 4): one dispatch, one event, and nothing else written. */
class LogWaterCommandTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val now = Instant.parse("2026-06-10T20:30:00Z")
    private lateinit var driver: SqlDriver
    private lateinit var events: EventRepository
    private lateinit var command: LogWaterCommand

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        events = SqlDelightEventRepository(MomTimeDatabase(driver))
        command = LogWaterCommand(events) { "water-1" }
    }

    @AfterTest
    fun tearDown() = driver.close()

    private fun count(table: String): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM $table",
                mapper = { cursor ->
                    check(cursor.next().value) { "no row" }
                    QueryResult.Value(checkNotNull(cursor.getLong(0)))
                },
                parameters = 0,
            ).value

    @Test
    fun `one dispatch writes one water event with the zone and nothing else`() {
        command.dispatch(now, zone)

        // The event as the store returns it: decoding a stored zone yields the zone that was given, so an event
        // written without it reads back with a null zone and differs here.
        assertEquals(
            Event(
                id = "water-1",
                occurrenceId = null,
                eventType = EventType.WATER_LOGGED,
                deviceTimestamp = now,
                effectiveAt = null,
                source = EventSource.USER,
                payload = EventPayload.Water(LogWaterCommand.GLASS_ML, zone),
            ),
            events.findById("water-1"),
        )
        assertEquals(250, LogWaterCommand.GLASS_ML)
        assertEquals(1, count("event"), "exactly one event")
        assertEquals(0, count("occurrence"), "no occurrence")
        assertEquals(0, count("schedule_template"), "no template")
        assertEquals(0, count("interruption_budget"), "no budget spent")
    }
}
