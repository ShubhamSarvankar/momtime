package com.momtime.shared.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Schema version 6 (ADR 0079, ADR 0086): an occurrence keeps its criticality, `WITHDRAWN` is a sixth, terminal state
 * with a partial unique index beside it, a completion records its template's nutrition tags and a water event its
 * zone. Forward migration tests from every prior version (invariant 2), with foreign keys enforced and ending in
 * `foreign_key_check` (ADR 0043). Versions 2 to 5 have no committed snapshot, so each is made the way it was made:
 * the committed version 1 baseline, migrated step by step. `AndroidSchemaTest` holds the twin of each test here.
 */
class SchemaV6Test {
    private val tempFiles = mutableListOf<Path>()
    private val drivers = mutableListOf<SqlDriver>()
    private val states = listOf("PENDING", "SNOOZED", "COMPLETED", "SKIPPED", "MISSED")
    private val criticalities = listOf("CRITICAL", "STANDARD", "GENTLE")
    private val copiedTables =
        listOf("pregnancy", "schedule_template", "schedule_template_nutrition_tag", "occurrence", "event")

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    private fun baselineCopy(): SqlDriver {
        val file = Files.createTempFile("momtime-v6", ".db").also { tempFiles.add(it) }
        Files.copy(Path.of("src/commonMain/sqldelight/databases/1.db"), file, StandardCopyOption.REPLACE_EXISTING)
        return openJvmSqliteDriver("jdbc:sqlite:$file").also { drivers.add(it) }
    }

    /** A database at [version] (1 to 5): the baseline, migrated step by step. */
    private fun at(version: Long): SqlDriver {
        val driver = baselineCopy()
        for (from in 1 until version) MomTimeDatabase.Schema.migrate(driver, from, from + 1)
        return driver
    }

    private fun fresh(): SqlDriver = JvmDatabaseDriverFactory.inMemory().createDriver().also { drivers.add(it) }

    /** A version 6 database that got there by migrating a seeded database at [from]. */
    private fun migrated(from: Long): SqlDriver {
        val driver = at(from)
        seed(driver, from)
        MomTimeDatabase.Schema.migrate(driver, from, 6)
        return driver
    }

    private fun exec(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.execute(null, sql, 0)
    }

    private fun rows(
        driver: SqlDriver,
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val out = mutableListOf<List<String?>>()
                    while (cursor.next().value) out += List(columns) { cursor.getString(it) }
                    QueryResult.Value(out.toList())
                },
                0,
            ).value

    private fun event(
        driver: SqlDriver,
        id: String,
        occurrenceId: String?,
        type: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        val occurrence = occurrenceId?.let { "'$it'" } ?: "NULL"
        val names = extra.keys.joinToString("") { ", $it" }
        val values = extra.values.joinToString("") { ", $it" }
        exec(
            driver,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source$names) " +
                "VALUES ('$id', $occurrence, '$type', 2000, 'USER'$values)",
        )
    }

    /**
     * A template of each criticality (the CRITICAL one with two tags, the STANDARD one with none, the GENTLE one
     * with one), an occurrence of each in every state the version knows, and one event of each type that has a
     * payload, with the columns the schema at [version] has: the canary instants arrive on the event in version 3
     * and a snooze's end in version 4. Two completions of the tagged template and one of the untagged.
     */
    private fun seed(
        driver: SqlDriver,
        version: Long,
    ) {
        exec(
            driver,
            "INSERT INTO pregnancy(id, phase, created_at, phase_changed_at) VALUES ('preg-1', 'PRENATAL', 0, 0)",
        )
        for ((t, criticality) in criticalities.withIndex()) {
            exec(
                driver,
                "INSERT INTO schedule_template(id, pregnancy_id, title, task_type, criticality, time_of_day, " +
                    "time_zone_id, recurrence_type) VALUES ('t-$criticality', 'preg-1', 'Iron tablet', " +
                    "'SUPPLEMENT', '$criticality', '08:00', 'Asia/Kolkata', 'DAILY')",
            )
            for ((s, state) in states.withIndex()) {
                exec(
                    driver,
                    "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, state, " +
                        "alarm_slot) VALUES ('o-$criticality-$state', 't-$criticality', '2026-01-0${s + 1}', " +
                        "${1000 + s}, 'Asia/Kolkata', '$state', ${t * 10 + s + 1})",
                )
            }
        }
        for ((template, tag) in listOf("t-CRITICAL" to "IRON", "t-CRITICAL" to "DAIRY", "t-GENTLE" to "FRUIT")) {
            exec(driver, "INSERT INTO schedule_template_nutrition_tag(template_id, tag) VALUES ('$template', '$tag')")
        }
        event(driver, "e-missed", "o-CRITICAL-MISSED", "MISSED", mapOf("effective_at" to "1500"))
        val snooze =
            mapOf("snooze_number" to "1") + if (version >= 4) mapOf("snoozed_until" to "602000") else emptyMap()
        event(driver, "e-snoozed", "o-CRITICAL-SNOOZED", "SNOOZED", snooze)
        event(
            driver,
            "e-mission",
            "o-CRITICAL-COMPLETED",
            "MISSION_VERIFIED",
            mapOf(
                "mission_result_type" to "'BARCODE'",
            ),
        )
        event(driver, "e-water", null, "WATER_LOGGED", mapOf("water_ml" to "250"))
        event(driver, "e-weight", null, "WEIGHT_LOGGED", mapOf("weight_grams" to "68000"))
        event(driver, "e-caregiver", null, "CAREGIVER_LINKED", mapOf("caregiver_link_id" to "'link-1'"))
        val canary =
            if (version >= 3) mapOf("canary_scheduled_at" to "100", "canary_actual_at" to "150") else emptyMap()
        event(driver, "e-canary", null, "CANARY_RESULT", canary)
        event(driver, "e-completed-tagged", "o-CRITICAL-COMPLETED", "COMPLETED")
        event(driver, "e-backfilled-tagged", "o-CRITICAL-MISSED", "COMPLETED_BACKFILLED")
        event(driver, "e-completed-untagged", "o-STANDARD-COMPLETED", "COMPLETED")
    }

    /** Every row of the tables a migration must carry over, restricted to the columns the table has now. */
    private fun columnsNow(driver: SqlDriver): Map<String, List<String>> =
        copiedTables.associateWith { table -> rows(driver, "PRAGMA table_info($table)", 2).map { checkNotNull(it[1]) } }

    private fun snapshot(
        driver: SqlDriver,
        columns: Map<String, List<String>>,
    ): Map<String, List<List<String?>>> =
        columns.mapValues { (table, names) ->
            rows(driver, "SELECT ${names.joinToString()} FROM $table ORDER BY 1, 2", names.size)
        }

    @Test
    fun `migrates from every prior version`() {
        for (version in 1L..5L) {
            val driver = at(version)
            seed(driver, version)
            val columns = columnsNow(driver)
            val before = snapshot(driver, columns)
            assertEquals(15, before.getValue("occurrence").size, "v$version: the seed is in place")
            assertEquals(10, before.getValue("event").size, "v$version: the seed is in place")

            MomTimeDatabase.Schema.migrate(driver, version, 6)

            assertEquals(emptyList(), driver.foreignKeyViolations(), "v$version: foreign key violations")
            assertEquals(before, snapshot(driver, columns), "v$version: an old row changed in a column it had")
        }
        assertEquals(6L, MomTimeDatabase.Schema.version)
    }

    @Test
    fun `the migration fills each new column from version 5 data`() {
        val driver = migrated(from = 5)
        val database = MomTimeDatabase(driver)
        val occurrences = SqlDelightOccurrenceRepository(database)
        val events = SqlDelightEventRepository(database)

        // Open and terminal alike: the fill runs with the terminal trigger dropped.
        for (criticality in criticalities) {
            for (state in states) {
                assertEquals(
                    Criticality.valueOf(criticality),
                    occurrences.findById("o-$criticality-$state")?.criticality,
                    "o-$criticality-$state takes its template's criticality",
                )
            }
        }

        // As sets: the order in which group_concat joined the names is unspecified (ADR 0079).
        val two = EventPayload.Completion(setOf(NutritionTag.IRON, NutritionTag.DAIRY))
        assertEquals(two, events.findById("e-completed-tagged")?.payload)
        assertEquals(two, events.findById("e-backfilled-tagged")?.payload)
        assertEquals(EventPayload.Completion(emptySet()), events.findById("e-completed-untagged")?.payload)
        assertEquals(
            listOf(listOf<String?>(null)),
            rows(driver, "SELECT nutrition_tags FROM event WHERE id = 'e-completed-untagged'", 1),
            "no tags is null and nothing else",
        )

        assertEquals(listOf(listOf<String?>(null)), rows(driver, "SELECT zone_id FROM event WHERE id = 'e-water'", 1))
        assertEquals(EventPayload.Water(250, zone = null), events.findById("e-water")?.payload)

        assertEquals(
            listOf(listOf("e-backfilled-tagged"), listOf("e-completed-tagged")),
            rows(driver, "SELECT id FROM event WHERE nutrition_tags IS NOT NULL ORDER BY id", 1),
            "no other event has tags",
        )
        assertEquals(
            emptyList(),
            rows(driver, "SELECT id FROM event WHERE zone_id IS NOT NULL", 1),
            "no event has a zone",
        )
        // Every migrated event still decodes: no column landed on a type that may not carry it.
        for (id in rows(driver, "SELECT id FROM event", 1).map { checkNotNull(it[0]) }) {
            assertEquals(id, events.findById(id)?.id)
        }
    }

    private val updates =
        listOf(
            "state" to "state = 'PENDING'",
            "state, unchanged" to "state = state",
            "scheduled_instant" to "scheduled_instant = scheduled_instant + 1",
            "time_zone_id" to "time_zone_id = 'Asia/Tokyo'",
            "local_date" to "local_date = date(local_date, '+1000 days')",
            "alarm_slot" to "alarm_slot = alarm_slot + 1000",
            "criticality" to "criticality = 'GENTLE'",
            "template_id, unchanged" to "template_id = template_id",
            "id" to "id = id || 'x'",
        )

    private fun occurrenceRow(
        driver: SqlDriver,
        id: String,
    ) = rows(driver, "SELECT * FROM occurrence WHERE id = '$id'", 8).single()

    /** Every update of [id] aborts with the trigger's message and leaves the row as it was. */
    private fun assertImmutable(
        driver: SqlDriver,
        id: String,
        label: String,
    ) {
        val before = occurrenceRow(driver, id)
        for ((column, set) in updates) {
            val failure =
                assertFailsWith<Exception>("$label, $column: the update went through") {
                    exec(driver, "UPDATE occurrence SET $set WHERE id = '$id'")
                }
            assertTrue(
                failure.message.orEmpty().contains("terminal occurrence is immutable"),
                "$label, $column: not the trigger's message: ${failure.message}",
            )
            assertEquals(before, occurrenceRow(driver, id), "$label, $column: the row changed anyway")
        }
    }

    private fun insertOccurrence(
        driver: SqlDriver,
        id: String,
        state: String,
        slot: Int,
    ) = exec(
        driver,
        "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, state, alarm_slot, " +
            "criticality) VALUES ('$id', 't-CRITICAL', '2027-01-${slot - 100}', 1000, 'Asia/Kolkata', '$state', " +
            "$slot, 'CRITICAL')",
    )

    @Test
    fun `a withdrawn row is immutable in full`() {
        val freshDriver = fresh()
        seed(freshDriver, 6)
        for ((label, driver) in listOf("fresh" to freshDriver, "migrated from 5" to migrated(from = 5))) {
            insertOccurrence(driver, "w", "WITHDRAWN", slot = 110)
            assertImmutable(driver, "w", "$label, WITHDRAWN")

            // The control: the same updates go through on an open row, so the aborts above are the trigger's.
            for ((index, update) in updates.withIndex()) {
                insertOccurrence(driver, "open-$index", "PENDING", slot = 111 + index)
                exec(driver, "UPDATE occurrence SET ${update.second} WHERE id = 'open-$index'")
            }
        }
    }

    @Test
    fun `the other terminal states are still immutable after the migration`() {
        for (from in listOf(1L, 5L)) {
            val driver = migrated(from)
            for (state in listOf("COMPLETED", "SKIPPED", "MISSED")) {
                assertImmutable(driver, "o-CRITICAL-$state", "from $from, $state")
            }
        }
    }

    @Test
    fun `a date can be materialised again after a withdrawal and not otherwise`() {
        val freshDriver = fresh()
        seed(freshDriver, 6)
        for ((label, driver) in listOf("fresh" to freshDriver, "migrated from 5" to migrated(from = 5))) {
            val database = MomTimeDatabase(driver)
            val occurrences = SqlDelightOccurrenceRepository(database)
            val at = Instant.fromEpochMilliseconds(5000)
            val first =
                Occurrence(
                    id = "first",
                    templateId = "t-GENTLE",
                    localDate = LocalDate(2027, 3, 1),
                    scheduledInstant = at,
                    timeZoneId = TimeZone.of("Asia/Kolkata"),
                    state = OccurrenceState.PENDING,
                    alarmSlot = 201,
                    criticality = Criticality.GENTLE,
                )
            occurrences.insert(first)
            occurrences.transition(
                "first",
                OccurrenceState.WITHDRAWN,
                Event("e-withdrawn", "first", EventType.WITHDRAWN, at, null, EventSource.USER, EventPayload.None),
            )
            assertEquals(OccurrenceState.WITHDRAWN, occurrences.findById("first")?.state, label)

            // The same template and date, a new id and a new slot: the withdrawn row does not stand in its way.
            occurrences.insert(first.copy(id = "second", alarmSlot = 202))
            assertEquals(OccurrenceState.PENDING, occurrences.findById("second")?.state, label)

            // A third for the date is a duplicate of the second, and the index refuses it.
            val failure =
                assertFailsWith<Exception>("$label: a second open occurrence for one date was stored") {
                    occurrences.insert(first.copy(id = "third", alarmSlot = 203))
                }
            assertTrue(
                failure.message.orEmpty().contains("occurrence.local_date"),
                "$label: not the date index: ${failure.message}",
            )
            assertNull(occurrences.findById("third"), label)
        }
    }
}
