package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import com.momtime.shared.domain.WaterGoal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Foreign keys are enforced, through the same factory production JVM code uses (ADR 0043). Before
 * this, `JdbcSqliteDriver` left SQLite's default (off) in place and every foreign key the schema
 * declares was decoration: an orphan row was accepted silently.
 *
 * The orphan inserts go through the repository layer, not raw SQL, so what is asserted is that the
 * constraint is live for the code paths the app uses.
 */
class ForeignKeyEnforcementTest {
    private val files = mutableListOf<Path>()
    private val drivers = mutableListOf<SqlDriver>()
    private val epoch = Instant.fromEpochMilliseconds(0)

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        files.forEach { Files.deleteIfExists(it) }
    }

    private fun inMemory() = JvmDatabaseDriverFactory.inMemory().createDriver().also { drivers.add(it) }

    private fun onFile(): SqlDriver {
        val file = Files.createTempFile("momtime-fk", ".db").also { files.add(it) }
        return JvmDatabaseDriverFactory("jdbc:sqlite:$file").createDriver().also { drivers.add(it) }
    }

    @Test
    fun `the factory turns foreign keys on for an in-memory and a file database`() {
        assertEquals("1", inMemory().pragma("foreign_keys"))
        assertEquals("1", onFile().pragma("foreign_keys"))
    }

    @Test
    fun `a caller cannot turn enforcement off through the properties`() {
        val driver =
            openJvmSqliteDriver(
                JdbcSqliteDriver.IN_MEMORY,
                Properties().apply { setProperty("foreign_keys", "false") },
            ).also { drivers.add(it) }
        assertEquals("1", driver.pragma("foreign_keys"))
    }

    private fun template() =
        ScheduleTemplate(
            id = "t-1",
            pregnancyId = "no-such-pregnancy",
            title = "Iron tablet",
            notes = null,
            taskType = TaskType.SUPPLEMENT,
            criticality = Criticality.CRITICAL,
            timeOfDay = LocalTime(8, 0),
            timeZoneId = TimeZone.of("Asia/Kolkata"),
            recurrence = Recurrence.Daily,
            mission = MissionConfig.None,
            nutritionTags = emptySet(),
            dosage = null,
            doctorInstructions = null,
            inventoryCount = null,
            refillThresholdDays = null,
            active = true,
        )

    private fun event(
        id: String,
        occurrenceId: String?,
    ) = Event(id, occurrenceId, EventType.ALARM_FIRED, epoch, null, EventSource.SYSTEM, EventPayload.None)

    @Test
    fun `the repository layer rejects an orphan row for every declared foreign key it can write`() {
        val driver = inMemory()
        val db = MomTimeDatabase(driver)
        val orphanOccurrence =
            Occurrence(
                "o-1",
                "no-such-template",
                LocalDate(2026, 1, 1),
                epoch,
                TimeZone.of("Asia/Kolkata"),
                OccurrenceState.PENDING,
                1,
            )
        val orphans: Map<String, () -> Unit> =
            mapOf(
                "template with no pregnancy" to { SqlDelightScheduleTemplateRepository(db).insert(template()) },
                "occurrence with no template" to { SqlDelightOccurrenceRepository(db).insert(orphanOccurrence) },
                "event with no occurrence" to
                    { SqlDelightEventRepository(db).insert(event("e-1", "no-such-occurrence")) },
                "water goal with no pregnancy" to
                    { SqlDelightWaterGoalRepository(db).upsert(WaterGoal("no-such-pregnancy", 2000, 3)) },
            )
        for ((name, insert) in orphans) {
            val failure = assertFailsWith<Exception>("an orphan was accepted: $name") { insert() }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains("FOREIGN KEY") },
                "$name failed, but not on the foreign key: $failure",
            )
        }
        assertEquals(emptyList(), driver.foreignKeyViolations())
    }

    @Test
    fun `an event with no occurrence is still allowed, since occurrence_id is nullable`() {
        val db = MomTimeDatabase(inMemory())
        SqlDelightEventRepository(db).insert(event("e-water", null))
        assertEquals("e-water", SqlDelightEventRepository(db).findById("e-water")?.id)
    }
}
