package com.momtime.shared.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionResultType
import com.momtime.shared.domain.NutritionTag
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Every EventPayload variant round-trips through the sparse columns correctly, not just None. */
class EventPayloadRoundTripTest {
    private lateinit var driverFactory: JvmDatabaseDriverFactory
    private lateinit var driver: SqlDriver
    private lateinit var database: MomTimeDatabase
    private lateinit var eventRepo: EventRepository

    @BeforeTest
    fun setUp() {
        driverFactory = JvmDatabaseDriverFactory.inMemory()
        driver = driverFactory.createDriver()
        database = MomTimeDatabase(driver)
        eventRepo = SqlDelightEventRepository(database)
    }

    @AfterTest
    fun tearDown() {
        driverFactory.createDriver().close()
    }

    // On SKIPPED since schema version 6: a COMPLETED row decodes as Completion, never as None (ADR 0086). The move
    // was authorised by Claude (technical review); the subject, that no payload round trips, is unchanged.
    @Test
    fun `none payload round trips`() = assertRoundTrip(EventPayload.None, EventType.SKIPPED)

    @Test
    fun `snooze payload round trips`() =
        assertRoundTrip(
            EventPayload.Snooze(snoozeNumber = 2, snoozedUntil = kotlin.time.Instant.fromEpochMilliseconds(600_000)),
            EventType.SNOOZED,
        )

    @Test
    fun `mission result payload round trips for both mission types`() {
        assertRoundTrip(EventPayload.MissionResult(MissionResultType.BARCODE), EventType.MISSION_VERIFIED)
        assertRoundTrip(EventPayload.MissionResult(MissionResultType.PHOTO_MATCH), EventType.MISSION_BYPASSED)
    }

    @Test
    fun `water payload round trips`() {
        assertRoundTrip(EventPayload.Water(waterMl = 250, zone = TimeZone.of("Asia/Kolkata")))
        // A row from before schema version 6 has no zone (ADR 0086).
        assertRoundTrip(EventPayload.Water(waterMl = 300, zone = null))
    }

    // Zero, one and all seven tags, on both completion types (ADR 0086). Equality of the payload is equality of
    // the tags as a set. The column is the names sorted and joined by commas, and null for none; the sets are
    // built in an order that is not sorted, so a writer that kept the set's own order would be seen.
    @Test
    fun `completion payload round trips and stores its tags sorted`() {
        val seven = linkedSetOf<NutritionTag>().apply { addAll(NutritionTag.entries.sortedByDescending { it.name }) }
        val cases =
            listOf(
                emptySet<NutritionTag>() to null,
                setOf(NutritionTag.IRON) to "IRON",
                linkedSetOf(NutritionTag.VEGETABLE, NutritionTag.DAIRY) to "DAIRY,VEGETABLE",
                seven to "CALCIUM,DAIRY,FRUIT,IRON,PROTEIN,SUPPLEMENT,VEGETABLE",
            )
        assertEquals(7, seven.size)
        assertEquals("VEGETABLE", seven.first().name, "the fixture's own order is not the sorted one")
        var n = 0
        for (type in listOf(EventType.COMPLETED, EventType.COMPLETED_BACKFILLED)) {
            for ((tags, column) in cases) {
                val id = "completion-${n++}"
                val event =
                    Event(
                        id,
                        null,
                        type,
                        Instant.fromEpochMilliseconds(0),
                        null,
                        EventSource.USER,
                        EventPayload.Completion(tags),
                    )
                eventRepo.insert(event)
                assertEquals(event, eventRepo.findById(id), "$type with $tags")
                assertEquals(column, storedTags(id), "$type with $tags: the stored column")
            }
        }
        assertEquals(8, n)
    }

    private fun storedTags(id: String): String? =
        driver
            .executeQuery(
                null,
                "SELECT nutrition_tags FROM event WHERE id = '$id'",
                { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(cursor.getString(0))
                },
                0,
            ).value

    @Test
    fun `weight payload round trips`() =
        assertRoundTrip(EventPayload.Weight(weightGrams = 68_000), EventType.WEIGHT_LOGGED)

    @Test
    fun `caregiver reference payload round trips`() =
        assertRoundTrip(EventPayload.CaregiverReference(caregiverLinkId = "link-1"), EventType.CAREGIVER_LINKED)

    @Test
    fun `canary payload round trips with and without an actual instant`() {
        val scheduled = Instant.fromEpochMilliseconds(1_000)
        assertRoundTrip(
            EventPayload.Canary(scheduledAt = scheduled, actualAt = Instant.fromEpochMilliseconds(1_250)),
            EventType.CANARY_RESULT,
        )
        // A canary never seen to fire: the actual instant is absent, not zero.
        assertRoundTrip(EventPayload.Canary(scheduledAt = scheduled, actualAt = null), EventType.CANARY_RESULT)
    }

    private fun assertRoundTrip(
        payload: EventPayload,
        type: EventType = EventType.WATER_LOGGED,
    ) {
        val id = "evt-${payload::class.simpleName}-${payload.hashCode()}"
        val event =
            Event(
                id = id,
                occurrenceId = null,
                eventType = type,
                deviceTimestamp = Instant.fromEpochMilliseconds(0),
                effectiveAt = null,
                source = EventSource.USER,
                payload = payload,
            )
        eventRepo.insert(event)
        val readBack = eventRepo.findById(id)
        assertEquals(event, readBack)
    }
}
