package com.momtime.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * ADR 0036: materialising a window is one atomic repository operation. These tests use a real
 * file database and two independent connections, because the in-memory test driver has a single
 * connection and cannot express a race at all.
 */
class MaterialiseAtomicityTest {
    private val dbFile = Files.createTempFile("momtime-atomicity", ".db")
    private val url = "jdbc:sqlite:$dbFile"
    private val zone = TimeZone.of("Asia/Kolkata")
    private val windowStart = Instant.parse("2026-01-01T00:00:00Z")
    private val windowEnd = windowStart + 10.days

    // Zero busy timeout: a lock conflict fails immediately instead of waiting, so the outcome of
    // the choreography below never depends on how long anything takes.
    private val props = Properties().apply { setProperty("busy_timeout", "0") }

    private lateinit var driverA: JdbcSqliteDriver
    private lateinit var driverB: JdbcSqliteDriver
    private lateinit var dbA: MomTimeDatabase
    private lateinit var dbB: MomTimeDatabase
    private lateinit var repoA: SqlDelightOccurrenceRepository
    private lateinit var repoB: SqlDelightOccurrenceRepository
    private lateinit var template: ScheduleTemplate

    @BeforeTest
    fun setUp() {
        driverA = JdbcSqliteDriver(url, props)
        MomTimeDatabase.Schema.create(driverA)
        driverB = JdbcSqliteDriver(url, props)
        dbA = MomTimeDatabase(driverA)
        dbB = MomTimeDatabase(driverB)
        repoA = SqlDelightOccurrenceRepository(dbA)
        repoB = SqlDelightOccurrenceRepository(dbB)

        val pregnancy =
            Pregnancy(
                "preg-1",
                PregnancyPhase.PRENATAL,
                Instant.fromEpochMilliseconds(0),
                Instant.fromEpochMilliseconds(0),
            )
        SqlDelightPregnancyRepository(dbA).insert(pregnancy)
        template = seedTemplate(pregnancy.id)
    }

    @AfterTest
    fun tearDown() {
        driverA.close()
        driverB.close()
        Files.deleteIfExists(dbFile)
    }

    private fun seedTemplate(pregnancyId: String): ScheduleTemplate {
        val template =
            ScheduleTemplate(
                id = "tmpl-1",
                pregnancyId = pregnancyId,
                title = "Iron tablet",
                notes = null,
                taskType = TaskType.SUPPLEMENT,
                criticality = Criticality.CRITICAL,
                timeOfDay = LocalTime(8, 0),
                timeZoneId = zone,
                recurrence = Recurrence.Daily,
                mission = MissionConfig.None,
                nutritionTags = setOf(NutritionTag.IRON),
                dosage = null,
                doctorInstructions = null,
                inventoryCount = null,
                refillThresholdDays = null,
                active = true,
            )
        SqlDelightScheduleTemplateRepository(dbA).insert(template)
        return template
    }

    private fun rows(): List<Occurrence> = repoA.findForTemplate(template.id)

    // Golden scenario 12's real-world form: the daily worker and an edit-triggered run overlap.
    //
    // Worker A reads the existing dates, then is paused (inside generateId, which the engine
    // calls after the read and before the insert). While A is paused, worker B, on its own
    // connection, tries to materialise the same window. A is then released. The interleaving is
    // forced by latches, not by timing.
    //
    // With the read and the inserts in one transaction, A holds its read lock across the pause, so
    // B cannot commit underneath it: B fails with SQLITE_BUSY (this JDBC driver begins DEFERRED
    // transactions, so it fails B rather than blocking it) and A completes. Without the
    // transaction, B commits all ten dates while A is paused, and A then inserts the same dates
    // and throws a UNIQUE violation, losing its batch.
    @Test
    fun `overlapping materialisation runs never duplicate and the paused run completes`() {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val aResult = AtomicReference<Result<List<Occurrence>>>()
        val aIds = AtomicInteger()
        val bIds = AtomicInteger()
        var firstCall = true

        val workerA =
            Thread {
                aResult.set(
                    runCatching {
                        repoA.materialiseWindow(template, windowStart, windowEnd) {
                            if (firstCall) {
                                firstCall = false
                                paused.countDown()
                                check(resume.await(10, TimeUnit.SECONDS)) { "worker A was never resumed" }
                            }
                            "a-${aIds.incrementAndGet()}"
                        }
                    },
                )
            }
        workerA.start()
        assertTrue(paused.await(10, TimeUnit.SECONDS), "worker A never reached the pause point")

        val bResult =
            runCatching { repoB.materialiseWindow(template, windowStart, windowEnd) { "b-${bIds.incrementAndGet()}" } }

        resume.countDown()
        workerA.join(10_000)
        assertTrue(!workerA.isAlive, "worker A did not finish")

        val a = assertNotNull(aResult.get(), "worker A produced no result")
        assertTrue(a.isSuccess, "worker A lost its batch: ${a.exceptionOrNull()}")

        // B may succeed (a serialising driver would have made it wait) or be refused with BUSY.
        // It must never fail any other way.
        val bFailure = bResult.exceptionOrNull()
        if (bFailure != null) {
            assertTrue(
                generateSequence(bFailure) { it.cause }.any { it.message.orEmpty().contains("BUSY") },
                "worker B failed with something other than SQLITE_BUSY: $bFailure",
            )
        }

        val all = rows()
        assertEquals(10, all.size, "the window must end with exactly ten occurrences")
        assertEquals(10, all.map { it.localDate }.toSet().size, "duplicate date")
        assertEquals(10, all.map { it.alarmSlot }.toSet().size, "duplicate alarmSlot")
    }

    // If an insert throws part way through a batch, nothing from the batch survives, including the
    // alarmSlot allocations. The failure is forced by returning an id that already exists as a
    // primary key for the third new occurrence.
    @Test
    fun `a failing insert rolls back the whole batch and its alarmSlots`() {
        repoA.materialiseWindow(template, windowStart, windowStart + 1.days) { "seed-1" }
        val before = rows()
        assertEquals(1, before.size)
        val slotBefore = repoA.allocateNextAlarmSlot()

        var calls = 0
        assertFailsWith<Exception> {
            repoA.materialiseWindow(template, windowStart, windowEnd) {
                // New dates are materialised in date order: the first two ids are fresh, the third
                // collides with the seeded row's primary key.
                calls++
                if (calls == 3) "seed-1" else "new-" + calls
            }
        }

        assertEquals(before, rows(), "a partial batch survived the failed operation")
        assertEquals(slotBefore + 1, repoA.allocateNextAlarmSlot(), "a failed batch burned alarmSlots")
    }

    // The engine's own filter must keep an existing date out of the batch. The last date of the
    // window is seeded first, so a duplicate-issuing engine would insert the nine earlier dates
    // before hitting the conflict. Whichever way the engine behaves, the table may only ever hold
    // the seed alone or the full ten: never a partial batch. A correct engine then succeeds.
    @Test
    fun `a window overlapping a later existing date adds only the missing dates`() {
        repoA.materialiseWindow(template, windowEnd - 1.days, windowEnd) { "seed-1" }
        assertEquals(1, rows().size)

        var n = 0
        val outcome = runCatching { repoA.materialiseWindow(template, windowStart, windowEnd) { "new-" + n++ } }

        assertTrue(rows().size == 1 || rows().size == 10, "partial batch left behind: ${rows().size} rows")
        assertTrue(outcome.isSuccess, "materialisation threw: ${outcome.exceptionOrNull()}")
        assertEquals(9, outcome.getOrThrow().size)
        assertEquals(10, rows().map { it.localDate }.toSet().size)
    }

    // The UNIQUE indexes are guarantees that fail loudly. A plain insert of an existing
    // (template_id, local_date) must throw rather than be silently absorbed.
    @Test
    fun `inserting a duplicate template and date throws`() {
        val created = repoA.materialiseWindow(template, windowStart, windowStart + 1.days) { "occ-1" }
        val original = created.single()

        assertFailsWith<Exception> {
            repoA.insert(
                original.copy(
                    id = "occ-duplicate",
                    alarmSlot = original.alarmSlot + 100,
                    state = OccurrenceState.PENDING,
                ),
            )
        }
        assertEquals(1, rows().size)
    }
}
