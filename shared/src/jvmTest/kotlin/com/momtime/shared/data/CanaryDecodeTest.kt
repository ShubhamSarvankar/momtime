package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The canary columns on `event` (ADR 0048) follow the table's convention: sparse nullable columns,
 * a row's payload being whichever column is set, with nothing in the schema tying a column to an event
 * type. So a mapping bug could set them on any event and nothing would notice. Decoding fails loudly
 * instead, on a canary column set on any event type but CANARY_RESULT and on a CANARY_RESULT with an
 * actual instant and no scheduled one. The rows are written with raw SQL, below the repository, because
 * the repository cannot produce them.
 */
class CanaryDecodeTest {
    private lateinit var driver: SqlDriver
    private lateinit var events: EventRepository

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        events = SqlDelightEventRepository(MomTimeDatabase(driver))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun raw(
        id: String,
        type: EventType,
        scheduled: String,
        actual: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, effective_at, source, " +
                "canary_scheduled_at, canary_actual_at) VALUES ('$id', NULL, '${type.name}', 0, NULL, 'SYSTEM', " +
                "$scheduled, $actual)",
            0,
        )
    }

    @Test
    fun `canary columns on any event type but CANARY_RESULT fail to decode`() {
        val others = EventType.entries.filter { it != EventType.CANARY_RESULT }
        assertTrue(others.size > 10, "the loop must cover the other event types")
        for (type in others) {
            raw("scheduled-$type", type, scheduled = "100", actual = "NULL")
            raw("actual-$type", type, scheduled = "NULL", actual = "150")
            for (id in listOf("scheduled-$type", "actual-$type")) {
                val failure = assertFailsWith<IllegalStateException>("$id decoded") { events.findById(id) }
                assertTrue(
                    failure.message.orEmpty().contains("canary columns are set on a $type event"),
                    failure.message,
                )
            }
        }
    }

    @Test
    fun `a canary with an actual instant and no scheduled one fails to decode`() {
        raw("bad", EventType.CANARY_RESULT, scheduled = "NULL", actual = "150")

        val failure = assertFailsWith<IllegalStateException> { events.findById("bad") }

        assertTrue(failure.message.orEmpty().contains("actual instant but no scheduled one"), failure.message)
    }

    @Test
    fun `a canary with only a scheduled instant, and one with neither, decode`() {
        raw("never-fired", EventType.CANARY_RESULT, scheduled = "200", actual = "NULL")
        raw("old-form", EventType.CANARY_RESULT, scheduled = "NULL", actual = "NULL")

        assertEquals(
            EventPayload.Canary(Instant.fromEpochMilliseconds(200), null),
            events.findById("never-fired")?.payload,
        )
        assertEquals(EventPayload.None, events.findById("old-form")?.payload)
    }
}
