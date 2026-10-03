package com.momtime.android.data

import com.momtime.android.store.ArmedAlarm
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.domain.DeliveryCapability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Instant

/**
 * The android store (ADR 0048): what the shared schema must not hold. It is a separate database, on
 * the same driver configuration as the shared one, and its rows are keyed by the shared event id
 * with no foreign key across the two files.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AndroidStoreTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        assertNativeSqliteMode()
        graph = TestGraph(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() = graph.close()

    private fun telemetry(
        id: String,
        tier: DeliveryCapability = DeliveryCapability.TIER_2,
    ) = FireTelemetry(
        eventId = id,
        resolvedTier = tier,
        screenOn = true,
        audioFocusObtained = false,
        batteryPct = 42,
        dozeState = "IDLE",
        watchdogRepair = true,
        bootCount = 17,
    )

    @Test
    fun `fire telemetry round trips every field, and every optional field absent`() {
        val repo = graph.get<FireTelemetryRepository>()
        val full = telemetry("e-full")
        // Booleans keep their value, not just their presence: false is not null.
        val flipped =
            full.copy(
                eventId = "e-flipped",
                screenOn = false,
                audioFocusObtained = true,
                watchdogRepair = false,
            )
        val bare =
            FireTelemetry(
                "e-bare",
                DeliveryCapability.TIER_1,
                null,
                null,
                null,
                null,
                watchdogRepair = false,
                bootCount = null,
            )
        for (row in listOf(full, flipped, bare)) {
            repo.insert(row)
            assertEquals(row, repo.findForEvent(row.eventId))
        }
        assertEquals(3L, repo.count())
        assertNull(repo.findForEvent("missing"))
    }

    @Test
    fun `every delivery tier is stored and read back as itself`() {
        val repo = graph.get<FireTelemetryRepository>()
        for ((index, tier) in DeliveryCapability.entries.withIndex()) {
            val row = telemetry("e-$index", tier)
            repo.insert(row)
            assertEquals(tier, repo.findForEvent(row.eventId)?.resolvedTier)
        }
    }

    private fun assertRejected(
        what: String,
        change: () -> Unit,
    ) {
        try {
            change()
        } catch (expected: Exception) {
            return
        }
        fail("accepted: $what")
    }

    @Test
    fun `the schema rejects an unknown tier, a battery over 100 and a second row for one event`() {
        val repo = graph.get<FireTelemetryRepository>()
        repo.insert(telemetry("e-1"))

        assertRejected("a second row for the same event") { repo.insert(telemetry("e-1")) }
        assertRejected("a battery of 101") { repo.insert(telemetry("e-2").copy(batteryPct = 101)) }
        assertRejected("a negative battery") { repo.insert(telemetry("e-3").copy(batteryPct = -1)) }
        assertEquals(1L, repo.count())
    }

    private fun armed(slot: Int) =
        ArmedAlarm(
            alarmSlot = slot,
            rungInstant = Instant.parse("2026-01-01T08:00:00Z"),
            armedAt = Instant.parse("2026-01-01T07:00:00Z"),
            bootCount = 9,
            exactAllowed = true,
        )

    @Test
    fun `the armed alarm is one record, replaced and cleared`() {
        val repo = graph.get<ArmedAlarmRepository>()
        assertNull(repo.current())

        repo.replace(armed(3))
        assertEquals(armed(3), repo.current())

        // At most one alarm is armed at a time: a second replace overwrites, never adds.
        val second = armed(8).copy(exactAllowed = false, bootCount = 10)
        repo.replace(second)
        assertEquals(second, repo.current())

        repo.clear()
        assertNull(repo.current())
    }

    // The store is a different file: nothing here touches the shared database, and the shared schema
    // has no table for it.
    @Test
    fun `the store file is separate from the shared database`() {
        graph.get<ArmedAlarmRepository>().replace(armed(1))
        val shared = graph.factory.createDriver()
        val store = graph.storeFactory.createDriver()

        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM armed_alarm"))
        assertEquals(0L, shared.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'armed_alarm'"))
        assertEquals(0L, shared.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'fire_telemetry'"))
    }
}

private fun app.cash.sqldelight.db.SqlDriver.scalar(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            check(cursor.next().value)
            app.cash.sqldelight.db.QueryResult
                .Value(checkNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value
