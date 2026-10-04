package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.EventType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every event type has its own set of allowed type dependent columns (EventColumn.kt, ADR 0052), and decoding
 * fails loudly if any other column is set. The table ties no column to a type, so this is the only thing that
 * does. The rows are written with raw SQL, below the repository, because the repository cannot produce them.
 *
 * The test sets each column on its own, on every event type, and expects a decode failure exactly when the type
 * does not allow that column. The expectation is written out here, per column, and does not read
 * `allowedColumns`: a rule that read the code under test could not disagree with it.
 */
class EventColumnGuardTest {
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

    /** Each column, the SQL value that sets it, and the types that may carry it, written out by hand. */
    private val columns: List<Triple<String, String, Set<EventType>>> =
        listOf(
            Triple("effective_at", "5", setOf(EventType.MISSED)),
            Triple("snooze_number", "1", setOf(EventType.SNOOZED)),
            Triple("snoozed_until", "600000", setOf(EventType.SNOOZED)),
            Triple("mission_result_type", "'BARCODE'", setOf(EventType.MISSION_VERIFIED, EventType.MISSION_BYPASSED)),
            Triple("water_ml", "250", setOf(EventType.WATER_LOGGED)),
            Triple("weight_grams", "68000", setOf(EventType.WEIGHT_LOGGED)),
            Triple(
                "caregiver_link_id",
                "'link-1'",
                setOf(
                    EventType.CAREGIVER_LINKED,
                    EventType.CAREGIVER_REVOKED,
                    EventType.SHARING_PAUSED,
                    EventType.SHARING_RESUMED,
                    EventType.CAREGIVER_NOTIFIED,
                ),
            ),
            Triple("canary_scheduled_at", "100", setOf(EventType.CANARY_RESULT)),
        )

    private fun raw(
        id: String,
        type: EventType,
        column: String,
        value: String,
    ) {
        driver.execute(
            null,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source, $column) " +
                "VALUES ('$id', NULL, '${type.name}', 0, 'SYSTEM', $value)",
            0,
        )
    }

    @Test
    fun `a column set on a type that does not allow it fails to decode, and on one that does it decodes`() {
        var allowedSeen = 0
        var refusedSeen = 0
        for ((column, value, allowed) in columns) {
            for (type in EventType.entries) {
                val id = "$column-$type"
                raw(id, type, column, value)
                if (type in allowed) {
                    assertEquals(type, events.findById(id)?.eventType, "$column on $type must decode")
                    allowedSeen++
                } else {
                    val failure =
                        assertFailsWith<IllegalStateException>("$column on $type decoded") {
                            events.findById(id)
                        }
                    assertTrue(failure.message.orEmpty().contains("are set on a $type event"), failure.message)
                    refusedSeen++
                }
            }
        }
        // The loop reached both outcomes for every column, so it is not vacuous in either direction.
        assertEquals(columns.sumOf { it.third.size }, allowedSeen)
        assertEquals(columns.size * EventType.entries.size - allowedSeen, refusedSeen)
    }

    @Test
    fun `effective_at on a COMPLETED event fails to decode`() {
        raw("completed", EventType.COMPLETED, "effective_at", "5")

        val failure = assertFailsWith<IllegalStateException> { events.findById("completed") }

        assertTrue(failure.message.orEmpty().contains("EFFECTIVE_AT"), failure.message)
    }

    @Test
    fun `an event type that carries nothing decodes with no column set`() {
        for (type in EventType.entries) {
            driver.execute(
                null,
                "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source) " +
                    "VALUES ('bare-$type', NULL, '${type.name}', 0, 'SYSTEM')",
                0,
            )
            assertEquals(type, events.findById("bare-$type")?.eventType)
        }
    }
}
