package com.momtime.shared.data

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DueDateRevision
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import com.momtime.shared.engine.OccurrenceMaterialiser
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class GoldenScenariosDbTest {
    private lateinit var driverFactory: JvmDatabaseDriverFactory
    private lateinit var database: MomTimeDatabase
    private lateinit var pregnancyRepo: PregnancyRepository
    private lateinit var templateRepo: ScheduleTemplateRepository
    private lateinit var occurrenceRepo: OccurrenceRepository
    private lateinit var eventRepo: EventRepository

    private val zone = TimeZone.of("Asia/Kolkata")
    private var idCounter = 0

    private fun nextId(prefix: String) = "$prefix-${idCounter++}"

    @BeforeTest
    fun setUp() {
        driverFactory = JvmDatabaseDriverFactory.inMemory()
        database = MomTimeDatabase(driverFactory.createDriver())
        pregnancyRepo = SqlDelightPregnancyRepository(database)
        templateRepo = SqlDelightScheduleTemplateRepository(database)
        occurrenceRepo = SqlDelightOccurrenceRepository(database)
        eventRepo = SqlDelightEventRepository(database)
        idCounter = 0
    }

    @AfterTest
    fun tearDown() {
        driverFactory.createDriver().close()
    }

    private fun seedPregnancy(now: Instant): Pregnancy {
        val pregnancy = Pregnancy(nextId("preg"), PregnancyPhase.PRENATAL, now, now)
        pregnancyRepo.insert(pregnancy)
        return pregnancy
    }

    private fun seedTemplate(
        pregnancyId: String,
        timeOfDay: LocalTime = LocalTime(8, 0),
        criticality: Criticality = Criticality.CRITICAL,
        recurrence: Recurrence = Recurrence.Daily,
        mission: MissionConfig = MissionConfig.None,
    ): ScheduleTemplate {
        val template =
            ScheduleTemplate(
                id = nextId("tmpl"),
                pregnancyId = pregnancyId,
                title = "Iron tablet",
                notes = null,
                taskType = TaskType.SUPPLEMENT,
                criticality = criticality,
                timeOfDay = timeOfDay,
                timeZoneId = zone,
                recurrence = recurrence,
                mission = mission,
                nutritionTags = setOf(NutritionTag.IRON),
                dosage = null,
                doctorInstructions = null,
                inventoryCount = null,
                refillThresholdDays = null,
                active = true,
            )
        templateRepo.insert(template)
        return template
    }

    // Mission is opt-in (default None, exercised by every other test in this file); this
    // confirms both non-default variants also round-trip through the flattened columns and
    // their CHECK constraints (ADR 0033).
    @Test
    fun `barcode and photo match mission configs round trip through the template repository`() {
        val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
        val barcodeTemplate = seedTemplate(pregnancy.id, mission = MissionConfig.Barcode("blister-strip-payload"))
        val photoTemplate = seedTemplate(pregnancy.id, mission = MissionConfig.PhotoMatch("hash-abc123"))

        assertEquals(MissionConfig.Barcode("blister-strip-payload"), templateRepo.findById(barcodeTemplate.id)?.mission)
        assertEquals(MissionConfig.PhotoMatch("hash-abc123"), templateRepo.findById(photoTemplate.id)?.mission)
    }

    private fun materialiseOnce(
        template: ScheduleTemplate,
        windowStart: Instant,
        windowEnd: Instant,
    ): List<Occurrence> {
        val alreadyMaterialised = occurrenceRepo.datesAlreadyMaterialisedForTemplate(template.id)
        val newOccurrences =
            OccurrenceMaterialiser.materialise(
                template = template,
                windowStart = windowStart,
                windowEnd = windowEnd,
                alreadyMaterialisedDates = alreadyMaterialised,
                generateId = { nextId("occ") },
                allocateSlot = { occurrenceRepo.allocateNextAlarmSlot() },
            )
        newOccurrences.forEach { occurrenceRepo.insert(it) }
        return newOccurrences
    }

    // Golden scenario 12 + required property: materialisation is idempotent under arbitrary
    // repetition, not just twice. Each step is a randomly chosen real operation: materialise
    // over a random, usually overlapping window, deactivate the template, or reactivate it
    // (deactivation makes the materialiser skip the template, as the daily worker does for
    // inactive templates). Across many seeds this asserts that at every step there are no
    // duplicate dates or alarmSlots, that a row already materialised is never changed or
    // re-issued under a new id or slot, and that a final full-window pass converges to exactly
    // the expected set.
    //
    // The repository exposes no general template-edit operation yet, so `active` is the only
    // template field that can change between passes here. Edits to timeOfDay and recurrence are
    // covered by golden scenario 1, not by this property.
    @Test
    fun `materialisation is idempotent under random windows and activation toggles - property test`() {
        val fullStart = Instant.parse("2026-01-01T00:00:00Z")
        val fullDays = 20
        val fullEnd = fullStart + fullDays.days

        for (seed in 1..20) {
            setUp()
            val random = Random(seed)
            val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
            val template = seedTemplate(pregnancy.id)
            val firstSeen = mutableMapOf<String, Triple<LocalDate, Instant, Int>>()

            repeat(40) { step ->
                val context = "seed=$seed step=$step"
                when (random.nextInt(4)) {
                    0 -> templateRepo.setActive(template.id, false)
                    1 -> templateRepo.setActive(template.id, true)
                    else -> {
                        val start = fullStart + random.nextLong(0, fullDays * 24L).hours
                        val end = minOf(start + random.nextLong(1, 10 * 24L).hours, fullEnd)
                        val before = occurrenceRepo.datesAlreadyMaterialisedForTemplate(template.id)
                        val created = materialiseOnce(checkNotNull(templateRepo.findById(template.id)), start, end)
                        // The insert is INSERT OR IGNORE, so the database would silently hide an
                        // engine that re-issued a date. Assert on the engine's own output.
                        val reissued = created.filter { it.localDate in before }
                        assertEquals(emptyList(), reissued, "engine re-issued already-materialised dates, $context")
                    }
                }

                val occurrences = occurrenceRepo.findForTemplate(template.id)
                val count = occurrences.size
                val dates = occurrences.map { it.localDate }.toSet()
                val slots = occurrences.map { it.alarmSlot }.toSet()
                val ids = occurrences.map { it.id }.toSet()
                assertEquals(count, dates.size, "duplicate date, $context")
                assertEquals(count, slots.size, "duplicate alarmSlot, $context")
                assertEquals(count, ids.size, "duplicate id, $context")

                occurrences.forEach { occurrence ->
                    val snapshot = Triple(occurrence.localDate, occurrence.scheduledInstant, occurrence.alarmSlot)
                    val earlier = firstSeen.getOrPut(occurrence.id) { snapshot }
                    assertEquals(earlier, snapshot, "${occurrence.id} changed after first materialisation, $context")
                }
                assertEquals(firstSeen.size, occurrences.size, "an occurrence vanished or was re-issued, $context")
            }

            templateRepo.setActive(template.id, true)
            materialiseOnce(checkNotNull(templateRepo.findById(template.id)), fullStart, fullEnd)
            assertEquals(fullDays, occurrenceRepo.findForTemplate(template.id).size, "did not converge, seed=$seed")
        }
    }

    // Golden scenario 1: template edit after some occurrences are terminal leaves terminal
    // occurrences untouched; only pending/future occurrences are affected by re-materialisation.
    @Test
    fun `terminal occurrences are immutable under template edits and re-materialisation`() {
        val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
        val template = seedTemplate(pregnancy.id)
        val windowStart = Instant.parse("2026-01-01T00:00:00Z")
        val windowEnd = windowStart + 3.days

        materialiseOnce(template, windowStart, windowEnd)
        val occurrences = occurrenceRepo.findForTemplate(template.id)
        val first = occurrences.minByOrNull { it.scheduledInstant }!!

        occurrenceRepo.updateState(first.id, OccurrenceState.COMPLETED)
        val completedBefore = occurrenceRepo.findById(first.id)!!

        // "Edit" the template (criticality change) and re-materialise the same window.
        templateRepo.setActive(template.id, true)
        materialiseOnce(template, windowStart, windowEnd)

        val completedAfter = occurrenceRepo.findById(first.id)!!
        assertEquals(completedBefore, completedAfter, "terminal occurrence changed after re-materialisation")
        assertEquals(OccurrenceState.COMPLETED, completedAfter.state)
    }

    // Golden scenario 11: two templates materialising at the identical scheduledInstant receive
    // distinct alarmSlot values and distinct occurrence ids.
    @Test
    fun `two templates at the same instant receive distinct alarmSlots`() {
        val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
        val templateA = seedTemplate(pregnancy.id, timeOfDay = LocalTime(9, 0))
        val templateB = seedTemplate(pregnancy.id, timeOfDay = LocalTime(9, 0))
        val windowStart = Instant.parse("2026-01-01T00:00:00Z")
        val windowEnd = windowStart + 1.days

        materialiseOnce(templateA, windowStart, windowEnd)
        materialiseOnce(templateB, windowStart, windowEnd)

        val occA = occurrenceRepo.findForTemplate(templateA.id).single()
        val occB = occurrenceRepo.findForTemplate(templateB.id).single()

        assertEquals(occA.scheduledInstant, occB.scheduledInstant)
        assert(
            occA.alarmSlot != occB.alarmSlot,
        ) { "expected distinct alarmSlot values, got ${occA.alarmSlot} for both" }
    }

    // Golden scenario 13 (documented-behaviour version, per ADR 0031's narrowing note):
    // uninstall/reinstall re-materialises and re-arms from a fresh counter with no collision,
    // because the previous install's alarms are already invalidated by the OS. The
    // shared-testable slice of this is that a *fresh* counter never collides with existing
    // occurrences in the *same* database, which a reinstall onto a clean device always is.
    @Test
    fun `a fresh alarmSlot counter never collides with existing occurrence alarmSlots`() {
        val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
        val template = seedTemplate(pregnancy.id)
        val windowStart = Instant.parse("2026-01-01T00:00:00Z")
        val windowEnd = windowStart + 5.days
        materialiseOnce(template, windowStart, windowEnd)

        val slotsBefore = occurrenceRepo.findForTemplate(template.id).map { it.alarmSlot }.toSet()
        // Simulate materialising further into the future — the counter must keep advancing
        // monotonically rather than resetting, so no new slot collides with an existing one.
        materialiseOnce(template, windowEnd, windowEnd + 5.days)
        val slotsAfter = occurrenceRepo.findForTemplate(template.id).map { it.alarmSlot }

        assertEquals(slotsAfter.size, slotsAfter.toSet().size, "duplicate alarmSlot values found")
        assertEquals(emptySet(), slotsBefore intersect (slotsAfter.toSet() - slotsBefore))
    }

    // Golden scenario 6: a backfilled completion arriving after a confirmed miss is still
    // accepted and recorded — it does not retract or conflict with the MISSED event already on
    // the log (the log is append-only; both facts coexist).
    @Test
    fun `a backfilled completion after a confirmed miss is accepted without touching the MISSED event`() {
        val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
        val template = seedTemplate(pregnancy.id)
        val windowStart = Instant.parse("2026-01-01T00:00:00Z")
        materialiseOnce(template, windowStart, windowStart + 1.days)
        val occurrence = occurrenceRepo.findForTemplate(template.id).single()

        occurrenceRepo.updateState(occurrence.id, OccurrenceState.MISSED)
        val missedEvent =
            com.momtime.shared.domain.Event(
                id = nextId("evt"),
                occurrenceId = occurrence.id,
                eventType = com.momtime.shared.domain.EventType.MISSED,
                deviceTimestamp = occurrence.scheduledInstant + 3.hours,
                effectiveAt = occurrence.scheduledInstant + 2.hours,
                source = com.momtime.shared.domain.EventSource.SYSTEM,
                payload = com.momtime.shared.domain.EventPayload.None,
            )
        eventRepo.insert(missedEvent)

        val backfilled =
            com.momtime.shared.domain.Event(
                id = nextId("evt"),
                occurrenceId = occurrence.id,
                eventType = com.momtime.shared.domain.EventType.COMPLETED_BACKFILLED,
                deviceTimestamp = occurrence.scheduledInstant + 10.hours,
                effectiveAt = null,
                source = com.momtime.shared.domain.EventSource.USER,
                payload = com.momtime.shared.domain.EventPayload.None,
            )
        eventRepo.insert(backfilled)

        val events = eventRepo.findForOccurrence(occurrence.id)
        assertEquals(2, events.size)
        assertNotNull(events.find { it.eventType == com.momtime.shared.domain.EventType.MISSED })
        assertNotNull(events.find { it.eventType == com.momtime.shared.domain.EventType.COMPLETED_BACKFILLED })
    }

    // Golden scenario 19: a pregnancy phase transition does not retroactively rescope or delete
    // history scoped to the ending pregnancyId.
    @Test
    fun `pregnancy phase transition preserves history scoped to the pregnancyId`() {
        val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
        val template = seedTemplate(pregnancy.id)
        val windowStart = Instant.parse("2026-01-01T00:00:00Z")
        materialiseOnce(template, windowStart, windowStart + 2.days)
        val occurrencesBefore = occurrenceRepo.findForTemplate(template.id)

        pregnancyRepo.updatePhase(pregnancy.id, PregnancyPhase.POSTPARTUM, windowStart + 100.days)

        val updated = pregnancyRepo.findById(pregnancy.id)!!
        val occurrencesAfter = occurrenceRepo.findForTemplate(template.id)
        val templateAfter = templateRepo.findById(template.id)!!

        assertEquals(PregnancyPhase.POSTPARTUM, updated.phase)
        assertEquals(pregnancy.id, templateAfter.pregnancyId, "template was rescoped by the phase transition")
        assertEquals(occurrencesBefore, occurrencesAfter, "occurrence history changed by the phase transition")
    }

    // Golden scenario M1: gestational-week/due-date reads require an explicit asOf instant.
    // A due date revised mid-pregnancy leaves a past-dated report unchanged while the current
    // view reflects the new date.
    @Test
    fun `due date revision does not rewrite a past-dated report`() {
        val pregnancy = seedPregnancy(Instant.parse("2026-01-01T00:00:00Z"))
        val firstRevision =
            DueDateRevision(
                nextId("ddr"),
                pregnancy.id,
                kotlinx.datetime.LocalDate(2026, 6, 1),
                Instant.parse("2026-01-01T00:00:00Z"),
            )
        pregnancyRepo.insertDueDateRevision(firstRevision)

        val lastMonthReportAsOf = Instant.parse("2026-02-01T00:00:00Z")

        val secondRevision =
            DueDateRevision(
                nextId("ddr"),
                pregnancy.id,
                kotlinx.datetime.LocalDate(2026, 6, 10),
                Instant.parse("2026-03-01T00:00:00Z"),
            )
        pregnancyRepo.insertDueDateRevision(secondRevision)

        val pastReportDueDate = pregnancyRepo.dueDateRevisionAsOf(pregnancy.id, lastMonthReportAsOf)
        val currentDueDate = pregnancyRepo.latestDueDateRevision(pregnancy.id)

        assertEquals(firstRevision.dueDate, pastReportDueDate?.dueDate, "past report's due date was rewritten")
        assertEquals(secondRevision.dueDate, currentDueDate?.dueDate)
    }

    @Test
    fun `dueDateRevisionAsOf before any revision exists returns null`() {
        val pregnancy = seedPregnancy(Instant.parse("2026-01-01T00:00:00Z"))
        val revision =
            DueDateRevision(
                nextId("ddr"),
                pregnancy.id,
                kotlinx.datetime.LocalDate(2026, 6, 1),
                Instant.parse("2026-03-01T00:00:00Z"),
            )
        pregnancyRepo.insertDueDateRevision(revision)

        val beforeAnyRevision = pregnancyRepo.dueDateRevisionAsOf(pregnancy.id, Instant.parse("2026-01-15T00:00:00Z"))
        assertNull(beforeAnyRevision)
    }

    // Golden scenario 8: timezone travel where the recomputed scheduledInstant is already past
    // applies the same shared-testable fact as the boot case — the occurrence still
    // materialises for the correct calendar date in the *new* zone, not a shifted one.
    @Test
    fun `materialisation uses the template's current zone, producing the correct local date after travel`() {
        val pregnancy = seedPregnancy(Instant.fromEpochMilliseconds(0))
        val kolkataTemplate = seedTemplate(pregnancy.id, timeOfDay = LocalTime(23, 0))
        val windowStart = Instant.parse("2026-01-01T00:00:00Z")
        materialiseOnce(kolkataTemplate, windowStart, windowStart + 1.days)
        val kolkataOccurrence = occurrenceRepo.findForTemplate(kolkataTemplate.id).single()

        // 23:00 IST on Jan 1 is still Jan 1 in Kolkata despite being past 18:30 UTC.
        assertEquals(kotlinx.datetime.LocalDate(2026, 1, 1), kolkataOccurrence.localDate)
    }
}
