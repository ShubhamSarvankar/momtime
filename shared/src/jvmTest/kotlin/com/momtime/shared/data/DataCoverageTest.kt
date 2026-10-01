package com.momtime.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.momtime.shared.domain.AlarmDeliveryTelemetry
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import com.momtime.shared.domain.WaterGoal
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Nullable-column round trips, absent-row lookups, and the schema CHECKs behind the sealed
 * Recurrence and MissionConfig variants. Telemetry comes first on purpose: the delivery SLO is
 * computed from those rows, so a field silently dropped on the way through corrupts the one
 * number the project is judged on.
 */
class DataCoverageTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: MomTimeDatabase
    private val zone = TimeZone.of("Asia/Kolkata")
    private val epoch = Instant.fromEpochMilliseconds(0)

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MomTimeDatabase.Schema.create(driver)
        db = MomTimeDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun eventRow(id: String) =
        Event(id, null, EventType.ALARM_FIRED, epoch, null, EventSource.SYSTEM, EventPayload.None)

    // ---- telemetry ---------------------------------------------------------------------------

    private val fullTelemetry =
        AlarmDeliveryTelemetry(
            eventId = "set-by-test",
            alarmSlot = 7,
            resolvedTier = DeliveryCapability.TIER_2,
            canaryScheduledAt = Instant.fromEpochMilliseconds(100),
            canaryActualAt = Instant.fromEpochMilliseconds(150),
            screenOn = true,
            audioFocusObtained = false,
            batteryPct = 42,
            dozeState = "IDLE",
        )

    @Test
    fun `telemetry round trips with every field set, every field null, and each field null alone`() {
        val events = SqlDelightEventRepository(db)
        val repo = SqlDelightAlarmDeliveryTelemetryRepository(db)
        val nullAlone: List<Pair<String, AlarmDeliveryTelemetry.() -> AlarmDeliveryTelemetry>> =
            listOf(
                "alarmSlot" to { copy(alarmSlot = null) },
                "resolvedTier" to { copy(resolvedTier = null) },
                "canaryScheduledAt" to { copy(canaryScheduledAt = null) },
                "canaryActualAt" to { copy(canaryActualAt = null) },
                "screenOn" to { copy(screenOn = null) },
                "audioFocusObtained" to { copy(audioFocusObtained = null) },
                "batteryPct" to { copy(batteryPct = null) },
                "dozeState" to { copy(dozeState = null) },
                // Booleans must keep their value, not just their presence: false is not null.
                "screenOn flipped" to { copy(screenOn = false, audioFocusObtained = true) },
            )
        val cases =
            listOf(
                "all set" to fullTelemetry,
                "all null" to
                    AlarmDeliveryTelemetry("x", null, null, null, null, null, null, null, null),
            ) + nullAlone.map { (name, change) -> "$name" to fullTelemetry.change() }

        cases.forEachIndexed { index, (name, telemetry) ->
            val id = "evt-$index"
            events.insert(eventRow(id))
            val row = telemetry.copy(eventId = id)
            repo.insert(row)
            assertEquals(row, repo.findForEvent(id), "telemetry case: $name")
        }
    }

    // ---- pregnancy, water, events, occurrences: present and absent --------------------------

    @Test
    fun `pregnancy round trips every phase and absent rows are null`() {
        val repo = SqlDelightPregnancyRepository(db)
        assertNull(repo.findById("missing"))
        assertNull(repo.latestDueDateRevision("missing"))

        PregnancyPhase.entries.forEachIndexed { index, phase ->
            val pregnancy =
                Pregnancy(
                    "preg-$index",
                    phase,
                    Instant.fromEpochMilliseconds(10L + index),
                    Instant.fromEpochMilliseconds(
                        20L + index,
                    ),
                )
            repo.insert(pregnancy)
            assertEquals(pregnancy, repo.findById(pregnancy.id))
        }
    }

    @Test
    fun `water goal, event and occurrence lookups return null when absent`() {
        assertNull(SqlDelightWaterGoalRepository(db).findForPregnancy("missing"))
        assertNull(SqlDelightEventRepository(db).findById("missing"))
        val occurrences = SqlDelightOccurrenceRepository(db)
        assertNull(occurrences.findById("missing"))
        assertNull(occurrences.findByTemplateAndDate("missing", LocalDate(2026, 1, 1)))
        assertNull(SqlDelightScheduleTemplateRepository(db).findById("missing"))
    }

    @Test
    fun `an occurrence is found by template and date`() {
        SqlDelightPregnancyRepository(db).insert(Pregnancy("preg-1", PregnancyPhase.PRENATAL, epoch, epoch))
        SqlDelightScheduleTemplateRepository(db).insert(template("t-1"))
        val occurrences = SqlDelightOccurrenceRepository(db)
        val created =
            occurrences.materialiseWindow(
                template("t-1"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
            ) { "occ-1" }
        assertEquals(created.single(), occurrences.findByTemplateAndDate("t-1", LocalDate(2026, 1, 1)))
        assertNull(occurrences.findByTemplateAndDate("t-1", LocalDate(2026, 1, 9)))
    }

    @Test
    fun `water goal round trips`() {
        val repo = SqlDelightWaterGoalRepository(db)
        val goal = WaterGoal("preg-1", dailyGoalMl = 2500, nudgeTimesPerDay = 4)
        repo.upsert(goal)
        assertEquals(goal, repo.findForPregnancy("preg-1"))
    }

    @Test
    fun `an event round trips with and without effectiveAt`() {
        val repo = SqlDelightEventRepository(db)
        val withEffective =
            Event(
                "e1",
                null,
                EventType.MISSED,
                Instant.fromEpochMilliseconds(5),
                Instant.fromEpochMilliseconds(3),
                EventSource.SYSTEM,
                EventPayload.None,
            )
        val without = withEffective.copy(id = "e2", effectiveAt = null)
        repo.insert(withEffective)
        repo.insert(without)
        assertEquals(withEffective, repo.findById("e1"))
        assertEquals(without, repo.findById("e2"))
    }

    // ---- templates: nullable columns and Recurrence ------------------------------------------

    private fun template(
        id: String,
        recurrence: Recurrence = Recurrence.Daily,
        full: Boolean = true,
    ) = ScheduleTemplate(
        id = id,
        pregnancyId = "preg-1",
        title = "Iron tablet",
        notes = if (full) "with food" else null,
        taskType = TaskType.SUPPLEMENT,
        criticality = Criticality.CRITICAL,
        timeOfDay = LocalTime(8, 0),
        timeZoneId = zone,
        recurrence = recurrence,
        mission = MissionConfig.None,
        nutritionTags = if (full) setOf(NutritionTag.IRON) else emptySet(),
        dosage = if (full) "1 tablet" else null,
        doctorInstructions = if (full) "after lunch" else null,
        inventoryCount = if (full) 30 else null,
        refillThresholdDays = if (full) 5 else null,
        active = true,
    )

    @Test
    fun `template round trips with and without its optional fields`() {
        val repo = SqlDelightScheduleTemplateRepository(db)
        repo.insert(template("t-full", full = true))
        repo.insert(template("t-bare", full = false))
        assertEquals(template("t-full", full = true), repo.findById("t-full"))
        assertEquals(template("t-bare", full = false), repo.findById("t-bare"))
    }

    @Test
    fun `weekly and every-N-days recurrences round trip through the database`() {
        val repo = SqlDelightScheduleTemplateRepository(db)
        val weekly = Recurrence.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY))
        val everyN = Recurrence.EveryNDays(n = 3, anchorDate = LocalDate(2026, 1, 30))
        repo.insert(template("t-weekly", weekly))
        repo.insert(template("t-everyn", everyN))
        assertEquals(weekly, repo.findById("t-weekly")?.recurrence)
        assertEquals(everyN, repo.findById("t-everyn")?.recurrence)
    }

    private fun rawTemplate(
        id: String,
        type: String,
        days: String? = null,
        n: String? = null,
        anchor: String? = null,
        mission: String = "'NONE'",
        barcode: String? = null,
        photo: String? = null,
    ) {
        fun lit(v: String?) = v?.let { "'$it'" } ?: "NULL"
        driver.execute(
            null,
            "INSERT INTO schedule_template(id, pregnancy_id, title, task_type, criticality, time_of_day, " +
                "time_zone_id, recurrence_type, recurrence_days_of_week, recurrence_n, recurrence_anchor_date, " +
                "mission_type, mission_barcode_payload, mission_photo_reference_hash) VALUES " +
                "('$id', 'preg-1', 'x', 'SUPPLEMENT', 'CRITICAL', '08:00', 'Asia/Kolkata', '$type', ${lit(days)}, " +
                "${n ?: "NULL"}, ${lit(anchor)}, $mission, ${lit(barcode)}, ${lit(photo)})",
            0,
        )
    }

    // Each recurrence variant accepts exactly its own column shape. A raw insert, below the
    // repository, so the CHECK is what is being tested and not the Kotlin mapping.
    @Test
    fun `recurrence CHECK accepts each valid shape and rejects every other`() {
        rawTemplate("ok-daily", "DAILY")
        rawTemplate("ok-weekly", "WEEKLY", days = "MONDAY,THURSDAY")
        rawTemplate("ok-everyn", "EVERY_N_DAYS", n = "3", anchor = "2026-01-01")

        val violations: Map<String, () -> Unit> =
            mapOf(
                "DAILY with days" to { rawTemplate("v1", "DAILY", days = "MONDAY") },
                "DAILY with n" to { rawTemplate("v2", "DAILY", n = "3") },
                "DAILY with anchor" to { rawTemplate("v3", "DAILY", anchor = "2026-01-01") },
                "WEEKLY without days" to { rawTemplate("v4", "WEEKLY") },
                "WEEKLY with n" to { rawTemplate("v5", "WEEKLY", days = "MONDAY", n = "3") },
                "WEEKLY with anchor" to { rawTemplate("v6", "WEEKLY", days = "MONDAY", anchor = "2026-01-01") },
                "EVERY_N_DAYS without n" to { rawTemplate("v7", "EVERY_N_DAYS", anchor = "2026-01-01") },
                "EVERY_N_DAYS without anchor" to { rawTemplate("v8", "EVERY_N_DAYS", n = "3") },
                "EVERY_N_DAYS with days" to
                    { rawTemplate("v9", "EVERY_N_DAYS", days = "MONDAY", n = "3", anchor = "2026-01-01") },
                "unknown type" to { rawTemplate("v10", "FORTNIGHTLY") },
            )
        for ((name, insert) in violations) {
            assertFailsWith<Exception>("recurrence CHECK accepted: $name") { insert() }
        }
    }

    @Test
    fun `mission CHECK accepts each valid shape and rejects every other`() {
        rawTemplate("ok-none", "DAILY")
        rawTemplate("ok-barcode", "DAILY", mission = "'BARCODE'", barcode = "payload")
        rawTemplate("ok-photo", "DAILY", mission = "'PHOTO_MATCH'", photo = "hash")

        val violations: Map<String, () -> Unit> =
            mapOf(
                "NONE with a barcode" to { rawTemplate("m1", "DAILY", barcode = "payload") },
                "BARCODE without a payload" to { rawTemplate("m2", "DAILY", mission = "'BARCODE'") },
                "BARCODE with a photo hash" to
                    { rawTemplate("m3", "DAILY", mission = "'BARCODE'", barcode = "p", photo = "h") },
                "PHOTO_MATCH without a hash" to { rawTemplate("m4", "DAILY", mission = "'PHOTO_MATCH'") },
                "unknown mission" to { rawTemplate("m5", "DAILY", mission = "'SQUATS'") },
            )
        for ((name, insert) in violations) {
            assertFailsWith<Exception>("mission CHECK accepted: $name") { insert() }
        }
    }
}
