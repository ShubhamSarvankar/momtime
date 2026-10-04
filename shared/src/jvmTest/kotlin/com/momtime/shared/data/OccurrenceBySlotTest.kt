package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * An alarm that fires names its occurrence only by its slot (ARCHITECTURE.md section 5.4). After a reset or a
 * restore the slot may belong to no occurrence, and the lookup must say so rather than fail.
 */
class OccurrenceBySlotTest {
    private lateinit var driver: SqlDriver
    private lateinit var occurrences: OccurrenceRepository
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val zone = TimeZone.UTC

    @BeforeTest
    fun setUp() {
        driver = JvmDatabaseDriverFactory.inMemory().createDriver()
        val database = MomTimeDatabase(driver)
        SqlDelightPregnancyRepository(database).insert(Pregnancy("preg", PregnancyPhase.PRENATAL, epoch, epoch))
        SqlDelightScheduleTemplateRepository(database).insert(
            ScheduleTemplate(
                id = "tmpl",
                pregnancyId = "preg",
                title = "Iron",
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
            ),
        )
        occurrences = SqlDelightOccurrenceRepository(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a slot finds the occurrence that owns it, and an unknown slot finds none`() {
        val occurrence =
            Occurrence("occ", "tmpl", LocalDate(2026, 1, 1), epoch, zone, OccurrenceState.PENDING, alarmSlot = 41)
        occurrences.insert(occurrence)

        assertEquals(occurrence, occurrences.findByAlarmSlot(41))
        assertNull(occurrences.findByAlarmSlot(42))
    }
}
