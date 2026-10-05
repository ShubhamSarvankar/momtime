package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Schema version 5 (ADR 0068): a terminal occurrence is immutable in full. Until version 5 the trigger refused a change
 * of state out of a terminal state and nothing else, so the schema allowed a terminal row's instant, zone, date or
 * slot to change. Forward migration tests from every prior version (invariant 2), with foreign keys enforced and
 * ending in `foreign_key_check` (ADR 0043). Versions 2, 3 and 4 have no committed snapshot, so each is made the way
 * it was made: the committed version 1 baseline, migrated step by step.
 */
class SchemaV5Test {
    private val tempFiles = mutableListOf<Path>()
    private val drivers = mutableListOf<SqlDriver>()

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    private fun baselineCopy(): SqlDriver {
        val file = Files.createTempFile("momtime-v5", ".db").also { tempFiles.add(it) }
        Files.copy(Path.of("src/commonMain/sqldelight/databases/1.db"), file, StandardCopyOption.REPLACE_EXISTING)
        return openJvmSqliteDriver("jdbc:sqlite:$file").also { drivers.add(it) }
    }

    private fun exec(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.execute(null, sql, 0)
    }

    /** A database at [version] (1 to 4): the baseline, migrated step by step. */
    private fun at(version: Long): SqlDriver {
        val driver = baselineCopy()
        for (from in 1 until version) MomTimeDatabase.Schema.migrate(driver, from, from + 1)
        return driver
    }

    private fun seed(driver: SqlDriver) {
        exec(
            driver,
            "INSERT INTO pregnancy(id, phase, created_at, phase_changed_at) VALUES ('preg-1', 'PRENATAL', 0, 0)",
        )
        exec(
            driver,
            "INSERT INTO schedule_template(id, pregnancy_id, title, task_type, criticality, time_of_day, " +
                "time_zone_id, recurrence_type) VALUES ('tmpl-1', 'preg-1', 'Iron tablet', 'SUPPLEMENT', " +
                "'CRITICAL', '08:00', 'Asia/Kolkata', 'DAILY')",
        )
        for ((index, state) in listOf("PENDING", "SNOOZED", "COMPLETED", "SKIPPED", "MISSED").withIndex()) {
            exec(
                driver,
                "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, state, " +
                    "alarm_slot) VALUES ('o-$state', 'tmpl-1', '2026-01-0${index + 1}', 1000, 'Asia/Kolkata', " +
                    "'$state', ${index + 1})",
            )
        }
    }

    private fun scheduledOf(
        driver: SqlDriver,
        id: String,
    ): Long =
        driver
            .executeQuery(
                null,
                "SELECT scheduled_instant FROM occurrence WHERE id = '$id'",
                { cursor ->
                    check(cursor.next().value)
                    app.cash.sqldelight.db.QueryResult
                        .Value(checkNotNull(cursor.getLong(0)))
                },
                0,
            ).value

    private fun assertV5(driver: SqlDriver) {
        for (state in listOf("COMPLETED", "SKIPPED", "MISSED")) {
            assertFailsWith<Exception>("$state: an update of the instant must abort") {
                exec(driver, "UPDATE occurrence SET scheduled_instant = 2000 WHERE id = 'o-$state'")
            }
            assertFailsWith<Exception>("$state: an update of the zone must abort") {
                exec(driver, "UPDATE occurrence SET time_zone_id = 'Asia/Tokyo' WHERE id = 'o-$state'")
            }
            assertEquals(1000L, scheduledOf(driver, "o-$state"), "$state: the row changed anyway")
        }
        for (state in listOf("PENDING", "SNOOZED")) {
            exec(driver, "UPDATE occurrence SET scheduled_instant = 2000 WHERE id = 'o-$state'")
            assertEquals(2000L, scheduledOf(driver, "o-$state"), "$state: an open occurrence still moves")
        }
        assertEquals(emptyList(), driver.foreignKeyViolations(), "the migration left foreign key violations")
    }

    // The gap this closes: at version 4 an update of a terminal row's instant went through.
    @Test
    fun `at version 4 a terminal row's instant could be changed`() {
        val driver = at(4)
        seed(driver)

        exec(driver, "UPDATE occurrence SET scheduled_instant = 2000 WHERE id = 'o-COMPLETED'")

        assertEquals(2000L, scheduledOf(driver, "o-COMPLETED"))
    }

    @Test
    fun `a version 4 database migrates to 5 and refuses every update of a terminal row`() {
        val driver = at(4)
        seed(driver)

        MomTimeDatabase.Schema.migrate(driver, 4, 5)

        assertV5(driver)
    }

    @Test
    fun `a version 3 database migrates to 5 in one run`() {
        val driver = at(3)
        seed(driver)

        MomTimeDatabase.Schema.migrate(driver, 3, 5)

        assertV5(driver)
    }

    @Test
    fun `a version 2 database migrates to 5 in one run`() {
        val driver = at(2)
        seed(driver)

        MomTimeDatabase.Schema.migrate(driver, 2, 5)

        assertV5(driver)
    }

    @Test
    fun `a version 1 database migrates to 5 in one run`() {
        val driver = at(1)
        seed(driver)

        MomTimeDatabase.Schema.migrate(driver, 1, 5)

        assertV5(driver)
    }

    @Test
    fun `a fresh database has the full trigger`() {
        val driver = JvmDatabaseDriverFactory.inMemory().createDriver().also { drivers.add(it) }
        seed(driver)

        assertV5(driver)
        assertTrue(MomTimeDatabase.Schema.version >= 5)
    }
}
