package com.momtime.android.data

import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.data.WaterGoalRepository
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.WaterGoal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The effective configuration of the production database on the Android driver (ADR 0043). The
 * foreign key and journal mode values are asserted, not assumed, so neither can change silently:
 * the framework leaves foreign keys off, and a default journal mode that became WAL would change how
 * the database backs up and how many connections the pool opens.
 *
 * What this shows is the behaviour of the Android framework's own SQLite code under Robolectric's
 * native runtime. It is not a device run, and the SQLite library is Robolectric's, not the device's.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AndroidDriverConfigurationTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        assertNativeSqliteMode()
        graph = TestGraph(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() = graph.close()

    @Test
    fun `sqlite runs in native mode`() = assertNativeSqliteMode()

    // The journal mode is set, not left to a default (ADR 0047): a rollback journal, TRUNCATE. Under
    // Robolectric the unset default is MEMORY, so a database that is not told its mode fails this,
    // and so does one put into WAL. The device default is TRUNCATE too (AOSP config.xml), but only an
    // explicit setting makes the value independent of it; MANUAL_CHECKS P2-6 holds the device remainder.
    @Test
    fun `the shared database has foreign keys on and a TRUNCATE rollback journal`() {
        val driver = graph.factory.createDriver()

        assertEquals("1", driver.pragma("foreign_keys"))
        assertEquals("truncate", driver.pragma("journal_mode").lowercase())
    }

    @Test
    fun `the android store has foreign keys on and a TRUNCATE rollback journal`() {
        val driver = graph.storeFactory.createDriver {}

        assertEquals("1", driver.pragma("foreign_keys"))
        assertEquals("truncate", driver.pragma("journal_mode").lowercase())
    }

    @Test
    fun `an orphan row is rejected by repositories`() {
        val orphans: Map<String, () -> Unit> =
            mapOf(
                "template with no pregnancy" to
                    { graph.get<ScheduleTemplateRepository>().insert(template(pregnancyId = "no-such-pregnancy")) },
                "event with no occurrence" to
                    {
                        graph.get<EventRepository>().insert(
                            Event(
                                "e-1",
                                "no-such-occurrence",
                                EventType.ALARM_FIRED,
                                epoch,
                                null,
                                EventSource.SYSTEM,
                                EventPayload.None,
                            ),
                        )
                    },
                "water goal with no pregnancy" to
                    { graph.get<WaterGoalRepository>().upsert(WaterGoal("no-such-pregnancy", 2000, 3)) },
            )
        for ((name, insert) in orphans) {
            try {
                insert()
                fail("an orphan was accepted: $name")
            } catch (expected: Exception) {
                val message = generateSequence<Throwable>(expected) { it.cause }.joinToString { it.message.orEmpty() }
                assertTrue("$name failed, but not on the foreign key: $message", message.contains("FOREIGN KEY"))
            }
        }
        assertEquals(emptyList<String>(), graph.factory.createDriver().foreignKeyViolations())
    }

    @Test
    fun `the schema opens and a repository works`() {
        graph.seedTemplate()
        assertEquals(emptyList<Any>(), graph.get<OccurrenceRepository>().findPending())
    }
}
