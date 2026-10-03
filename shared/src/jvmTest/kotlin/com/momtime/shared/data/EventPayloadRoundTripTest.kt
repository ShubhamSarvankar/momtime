package com.momtime.shared.data

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionResultType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Every EventPayload variant round-trips through the sparse columns correctly, not just None. */
class EventPayloadRoundTripTest {
    private lateinit var driverFactory: JvmDatabaseDriverFactory
    private lateinit var database: MomTimeDatabase
    private lateinit var eventRepo: EventRepository

    @BeforeTest
    fun setUp() {
        driverFactory = JvmDatabaseDriverFactory.inMemory()
        database = MomTimeDatabase(driverFactory.createDriver())
        eventRepo = SqlDelightEventRepository(database)
    }

    @AfterTest
    fun tearDown() {
        driverFactory.createDriver().close()
    }

    @Test
    fun `none payload round trips`() = assertRoundTrip(EventPayload.None)

    @Test
    fun `snooze payload round trips`() = assertRoundTrip(EventPayload.Snooze(snoozeNumber = 2))

    @Test
    fun `mission result payload round trips for both mission types`() {
        assertRoundTrip(EventPayload.MissionResult(MissionResultType.BARCODE))
        assertRoundTrip(EventPayload.MissionResult(MissionResultType.PHOTO_MATCH))
    }

    @Test
    fun `water payload round trips`() = assertRoundTrip(EventPayload.Water(waterMl = 250))

    @Test
    fun `weight payload round trips`() = assertRoundTrip(EventPayload.Weight(weightGrams = 68_000))

    @Test
    fun `caregiver reference payload round trips`() =
        assertRoundTrip(EventPayload.CaregiverReference(caregiverLinkId = "link-1"))

    @Test
    fun `canary payload round trips with and without an actual instant`() {
        val scheduled = Instant.fromEpochMilliseconds(1_000)
        assertRoundTrip(EventPayload.Canary(scheduledAt = scheduled, actualAt = Instant.fromEpochMilliseconds(1_250)))
        // A canary never seen to fire: the actual instant is absent, not zero.
        assertRoundTrip(EventPayload.Canary(scheduledAt = scheduled, actualAt = null))
    }

    private fun assertRoundTrip(payload: EventPayload) {
        val id = "evt-${payload::class.simpleName}-${payload.hashCode()}"
        val event =
            Event(
                id = id,
                occurrenceId = null,
                eventType = EventType.WATER_LOGGED,
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
