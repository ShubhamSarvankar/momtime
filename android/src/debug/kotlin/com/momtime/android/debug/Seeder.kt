package com.momtime.android.debug

import com.momtime.android.arming.ArmingCoordinator
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** What seeding did. */
sealed interface SeedResult {
    /** [occurrences] open occurrences now exist for the seeded templates, and one alarm was armed. */
    data class Seeded(
        val occurrences: Int,
    ) : SeedResult

    /** The seed templates are already there; nothing was changed. */
    data object AlreadySeeded : SeedResult
}

/**
 * The debug only seed (ADR 0072). Phase 3's schedule builder does not exist yet, so a real device has no way to get a
 * reminder in front of a real alarm. This seeds, through the domain's own repositories and commands and nothing else:
 *
 * - one **CRITICAL** test reminder, three to four minutes from now: a template that is inactive from the start (so the
 *   daily materialisation never adds a second day), materialised once for its one occurrence;
 * - three **daily STANDARD** reminders, at 08:00, 13:00 and 20:00 in the device's zone, which the daily job keeps
 *   materialising.
 *
 * Then it materialises and calls `ensureArmed`, so exactly one alarm is armed, for the earliest rung. It lives in the
 * `debug` source set and is kept past Phase 3 as a debug tool unless Phase 3 decides otherwise (Claude (technical
 * review)); `verifyNoDebugComponents` fails the build if any of it reaches the release manifest.
 */
class Seeder(
    private val pregnancies: PregnancyRepository,
    private val templates: ScheduleTemplateRepository,
    private val occurrences: OccurrenceRepository,
    private val materialise: MaterialiseCommand,
    private val coordinator: ArmingCoordinator,
    private val clock: Clock,
    private val zone: () -> TimeZone,
    private val newId: () -> String,
) {
    fun seed(): SeedResult {
        if (templates.findById(TEST_ID) != null) return SeedResult.AlreadySeeded
        val now = clock.now()
        val zone = zone()
        if (pregnancies.findById(PREGNANCY_ID) == null) {
            pregnancies.insert(Pregnancy(PREGNANCY_ID, PregnancyPhase.PRENATAL, now, now))
        }

        // The test reminder: due at the next whole minute at least three minutes from now, so three to four minutes out.
        val due = (now + 4.minutes).toLocalDateTime(zone)
        val test = template(TEST_ID, "Test reminder", Criticality.CRITICAL, LocalTime(due.hour, due.minute), zone)
        templates.insert(test)
        occurrences.materialiseWindow(test, now, now + 5.minutes, newId)
        // Inactive from here on: the daily materialisation skips it, so it is a one off. Its open occurrence still rings.
        templates.setActive(TEST_ID, false)

        DAILY.forEachIndexed { index, time ->
            templates.insert(
                template("$DAILY_PREFIX${index + 1}", "Daily reminder ${index + 1}", Criticality.STANDARD, time, zone),
            )
        }
        materialise.dispatch(now)
        coordinator.ensureArmed()
        return SeedResult.Seeded(occurrences.findOpen().size)
    }

    private fun template(
        id: String,
        title: String,
        criticality: Criticality,
        at: LocalTime,
        zone: TimeZone,
    ) = ScheduleTemplate(
        id = id,
        pregnancyId = PREGNANCY_ID,
        title = title,
        notes = null,
        taskType = TaskType.SUPPLEMENT,
        criticality = criticality,
        timeOfDay = at,
        timeZoneId = zone,
        recurrence = Recurrence.Daily,
        mission = MissionConfig.None,
        nutritionTags = emptySet<NutritionTag>(),
        dosage = null,
        doctorInstructions = null,
        inventoryCount = null,
        refillThresholdDays = null,
        active = true,
    )

    companion object {
        const val PREGNANCY_ID = "debug-seed-pregnancy"
        const val TEST_ID = "debug-seed-test"
        const val DAILY_PREFIX = "debug-seed-daily-"
        val DAILY = listOf(LocalTime(8, 0), LocalTime(13, 0), LocalTime(20, 0))
    }
}
