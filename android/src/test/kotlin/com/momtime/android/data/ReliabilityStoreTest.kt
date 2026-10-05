package com.momtime.android.data

import android.content.Context
import com.momtime.android.store.ArmingContext
import com.momtime.android.store.ArmingContextRepository
import com.momtime.android.store.CheckOutcome
import com.momtime.android.store.ClockChangeRepository
import com.momtime.android.store.CountingStoreFailures
import com.momtime.android.store.ReliabilityCheckRepository
import com.momtime.android.store.StoreFailureRepository
import com.momtime.android.store.StoreFailures
import com.momtime.shared.domain.DeliveryCapability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Instant

/**
 * What the reliability evidence keeps in the android store (ADR 0070): the check's history, the persisted store failure
 * counts, the arming context and the clock change count. The store runs strict in these tests, so a failing call
 * throws.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class ReliabilityStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()
    private val at = Instant.fromEpochMilliseconds(1_000_000)

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    private fun graph(
        storeName: String = "rel-${System.nanoTime()}.db",
        failures: StoreFailures = StrictStoreFailures,
    ) = TestGraph(context, storeName = storeName, storeFailures = failures).also { graphs.add(it) }

    /** A count that outlives the process that saw it: a new instance over the same store reads what was written. */
    @Test
    fun `store failure counts persist, so a new instance reading the store sees them`() {
        val storeName = "failures-${System.nanoTime()}.db"
        val first = CountingStoreFailures { }
        val firstGraph = graph(storeName, first)
        firstGraph.get<StoreFailures>()
        first.onFailure("fire_telemetry.insert", IllegalStateException("one"))
        first.onFailure("fire_telemetry.insert", IllegalStateException("two"))
        first.onFailure("armed_alarm.replace", IllegalStateException("three"))
        assertEquals(mapOf("fire_telemetry.insert" to 2L, "armed_alarm.replace" to 1L), first.counts())
        firstGraph.close()
        graphs.remove(firstGraph)

        val second = CountingStoreFailures { }
        val secondGraph = graph(storeName, second)
        secondGraph.get<StoreFailures>()

        assertEquals(
            "a new instance, as after the process ended",
            mapOf("fire_telemetry.insert" to 2L, "armed_alarm.replace" to 1L),
            second.counts(),
        )
        assertEquals(3L, second.total)
        second.onFailure("armed_alarm.replace", IllegalStateException("four"))
        assertEquals(2L, second.counts()["armed_alarm.replace"])
    }

    @Test
    fun `a failure that could not be persisted is kept in memory and added to the persisted ones`() {
        val down =
            object : StoreFailureRepository {
                var up = false
                private val stored = mutableMapOf<String, Long>()

                override fun increment(operation: String): Boolean {
                    if (!up) return false
                    stored[operation] = (stored[operation] ?: 0L) + 1
                    return true
                }

                override fun counts(): Map<String, Long> = stored.toMap()
            }
        val counting = CountingStoreFailures { }
        counting.persistThrough { down }

        counting.onFailure("a", IllegalStateException())
        counting.onFailure("a", IllegalStateException())
        assertEquals("nothing could be stored, so memory holds them", mapOf("a" to 2L), counting.counts())

        down.up = true
        counting.onFailure("a", IllegalStateException())
        assertEquals("one persisted, two still held", mapOf("a" to 3L), counting.counts())
    }

    @Test
    fun `a counting policy with nothing to persist through counts in memory`() {
        val counting = CountingStoreFailures { }
        counting.onFailure("a", IllegalStateException())
        assertEquals(mapOf("a" to 1L), counting.counts())
    }

    @Test
    fun `the check history keeps one pending check, settles it once, and lists newest first`() {
        val checks = graph().get<ReliabilityCheckRepository>()

        val first = checkNotNull(checks.startPending(at, DeliveryCapability.TIER_3))
        assertNull("one at a time", checks.startPending(at, DeliveryCapability.TIER_3))
        assertEquals(CheckOutcome.PENDING, checks.pending()?.outcome)

        assertTrue(checks.markFired(first, at))
        assertFalse("settled once", checks.markFired(first, at))
        assertFalse("and not again as missed", checks.markMissed(first))
        assertNull(checks.pending())

        val second = checkNotNull(checks.startPending(at, DeliveryCapability.TIER_2))
        assertTrue(checks.markMissed(second))
        assertFalse(checks.markMissed(second))

        val third = checkNotNull(checks.startPending(at, DeliveryCapability.TIER_1))
        assertTrue(checks.retier(third, DeliveryCapability.TIER_2))
        assertEquals(DeliveryCapability.TIER_2, checks.pending()?.resolvedTier)
        assertTrue(checks.abandon(third))
        assertFalse("nothing left to abandon", checks.abandon(third))

        assertEquals(listOf(CheckOutcome.MISSED, CheckOutcome.FIRED), checks.recent(10).map { it.outcome })
        assertEquals(listOf(second, first), checks.recent(10).map { it.id })
        assertEquals(1, checks.recent(1).size)
        assertEquals(at, checks.recent(10).last().firedAt)
        assertNull("a missed check has no fire instant", checks.recent(10).first().firedAt)
    }

    @Test
    fun `a settled check cannot be retiered or abandoned`() {
        val checks = graph().get<ReliabilityCheckRepository>()
        val id = checkNotNull(checks.startPending(at, DeliveryCapability.TIER_3))
        assertTrue(checks.markFired(id, at))

        assertFalse(checks.retier(id, DeliveryCapability.TIER_1))
        assertFalse(checks.abandon(id))
        assertEquals(DeliveryCapability.TIER_3, checks.recent(1).single().resolvedTier)
    }

    @Test
    fun `the schema holds a check to its shape`() {
        val driver = graph().storeFactory.createDriver {}

        fun insert(
            outcome: String,
            firedAt: String,
            tier: String = "TIER_3",
        ) = driver.execute(
            null,
            "INSERT INTO reliability_check(scheduled_at, fired_at, outcome, resolved_tier) " +
                "VALUES (1, $firedAt, '$outcome', '$tier')",
            0,
        )

        for (bad in listOf(
            Triple("FIRED", "NULL", "TIER_3"),
            Triple("MISSED", "5", "TIER_3"),
            Triple("PENDING", "5", "TIER_3"),
            Triple("OTHER", "NULL", "TIER_3"),
            Triple("MISSED", "NULL", "TIER_9"),
        )) {
            val refused = runCatching { insert(bad.first, bad.second, bad.third) }.isFailure
            assertTrue("$bad must be refused by the schema", refused)
        }
        insert("PENDING", "NULL")
        assertTrue("a second pending is refused by the index", runCatching { insert("PENDING", "NULL") }.isFailure)
    }

    @Test
    fun `the arming context is keyed by the arming event and is replaced, not duplicated`() {
        val contexts = graph().get<ArmingContextRepository>()
        assertNull(contexts.find("e1"))

        val first = ArmingContext("e1", at, bootCount = 7, clockChanges = 2)
        assertTrue(contexts.record(first))
        assertEquals(first, contexts.find("e1"))
        val again = first.copy(bootCount = 8)
        assertTrue(contexts.record(again))
        assertEquals(again, contexts.find("e1"))
        assertNull(contexts.find("e2"))
    }

    @Test
    fun `the clock change count only goes up`() {
        val clock = graph().get<ClockChangeRepository>()
        assertEquals(0L, clock.count())
        assertTrue(clock.record(at))
        assertTrue(clock.record(at))
        assertEquals(2L, clock.count())
    }
}
