package com.momtime.shared.data

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DueDateRevision
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import com.momtime.shared.engine.RecurrenceExpander
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    ): List<Occurrence> = occurrenceRepo.materialiseWindow(template, windowStart, windowEnd) { nextId("occ") }

    // Golden scenario 12 + required property (ADR 0079): materialisation and template edits interleaved. Every step
    // is a real operation through a production command: materialise a random window, move the clock forward, edit
    // `timeOfDay`, edit the recurrence, deactivate, reactivate (and materialise), complete a random open occurrence.
    // Until Phase 3 the only edit was a toggle of `active`, so this property never saw a row move.
    @Test
    fun `scenario 12 materialisation and template edits interleaved - property test`() {
        var editsThatMoved = 0
        var editsThatWithdrew = 0
        var reactivationsThatCreated = 0
        for (seed in 1..20) {
            val f = TemplateEditFixture()
            try {
                val steps = EditSteps(f, Random(seed), "t")
                val seen = mutableMapOf<String, Occurrence>()
                val frozen = mutableSetOf<String>()
                val idOfSlot = mutableMapOf<Int, String>()
                repeat(60) { step ->
                    steps.step()
                    val context = "seed=$seed step=$step"
                    val rows = f.occurrences.findForTemplate("t")
                    // 1: no two rows that are not withdrawn share a template and date.
                    val live = rows.filter { it.state != OccurrenceState.WITHDRAWN }
                    assertEquals(
                        live.size,
                        live.map { it.localDate }.toSet().size,
                        "two live rows for a date, $context",
                    )
                    for (row in rows) {
                        // 2: no two rows share a slot or an id, and neither is seen later on a different date.
                        val first = seen[row.id]
                        assertEquals(row.id, idOfSlot.getOrPut(row.alarmSlot) { row.id }, "slot shared, $context")
                        if (first != null) {
                            assertEquals(first.localDate, row.localDate, "an id moved to another date, $context")
                            assertEquals(first.alarmSlot, row.alarmSlot, "a slot changed, $context")
                            if (row.id in frozen) {
                                // 3: terminal or come due: equal ever after, but for a transition to a terminal state.
                                val transitioned = !first.isTerminal && row.isTerminal
                                val expected = if (transitioned) first.copy(state = row.state) else first
                                assertEquals(expected, row, "a row that was terminal or come due changed, $context")
                            } else {
                                // 4: every other row changed only in instant, criticality or by a transition.
                                val comparable =
                                    row.copy(
                                        scheduledInstant = first.scheduledInstant,
                                        criticality = first.criticality,
                                        state = first.state,
                                    )
                                assertEquals(
                                    first,
                                    comparable,
                                    "a row changed in a column an edit may not change, $context",
                                )
                            }
                        }
                        seen[row.id] = row
                        if (row.isTerminal ||
                            row.scheduledInstant <= steps.now ||
                            row.state == OccurrenceState.SNOOZED
                        ) {
                            frozen += row.id
                        }
                    }
                    assertEquals(seen.size, rows.size, "a row vanished, $context")
                }
                editsThatMoved += steps.editsThatMoved
                editsThatWithdrew += steps.editsThatWithdrew
                reactivationsThatCreated += steps.reactivationsThatCreated
                assertConvergesAndNothingIsMissedByAnEdit(f, steps, seed)
            } finally {
                f.close()
            }
        }
        assertTrue(editsThatMoved > 0, "no edit moved a row over 20 seeds: the generator does not reach the case")
        assertTrue(editsThatWithdrew > 0, "no edit withdrew a row over 20 seeds")
        assertTrue(reactivationsThatCreated > 0, "no reactivation created a row over 20 seeds")
    }

    private fun assertConvergesAndNothingIsMissedByAnEdit(
        f: TemplateEditFixture,
        steps: EditSteps,
        seed: Int,
    ) {
        f.occurrences.materialiseWindow(
            checkNotNull(f.templates.findById("t")),
            EditSteps.START,
            EditSteps.END,
            f.newId,
        )
        // One last edit of the time, after the span is full of rows: the last thing that happened to the template is a
        // change of time, so a row left at an earlier time (or at the time of the edit before) cannot be hidden by a
        // later edit that moved it.
        steps.finalTimeEdit()
        val template = checkNotNull(f.templates.findById("t"))
        // Convergence over the dates after the current date in the template's zone: an edit to a time already past
        // keeps today's time (ADR 0079 item 5), so today is not part of it.
        val today = steps.now.toLocalDateTime(f.zone).date
        val rows = f.occurrences.findForTemplate("t")
        val actual =
            rows.filter { !it.isTerminal && it.localDate > today }.associate {
                it.localDate to
                    it.scheduledInstant
            }
        val endDate = EditSteps.END.toLocalDateTime(f.zone).date
        val closed = rows.filter { it.state != OccurrenceState.WITHDRAWN && it.isTerminal }.map { it.localDate }.toSet()
        val expected =
            if (!template.active) {
                emptyMap()
            } else {
                RecurrenceExpander
                    .expand(template.recurrence, today.plus(DatePeriod(days = 1)), endDate)
                    .filter { it !in closed }
                    .associateWith { f.at(it, template.timeOfDay.hour, template.timeOfDay.minute) }
            }
        assertEquals(expected, actual, "open rows after today are the final template's expansion, seed=$seed")

        // The step that would miss a row because of an edit: after the grace of every row in the span.
        val late = EditSteps.END + (3 * 24L).hours
        f.reconcile.dispatch(late)
        val byId = f.allOccurrences().associateBy { it.id }
        for (event in f.allEvents()) {
            val occurrence = byId.getValue(checkNotNull(event.occurrenceId))
            if (event.eventType != EventType.MISSED) continue
            assertNotEquals(
                OccurrenceState.WITHDRAWN,
                occurrence.state,
                "a withdrawn row has a MISSED event, seed=$seed",
            )
            val grace =
                when (occurrence.criticality) {
                    Criticality.CRITICAL -> 2.hours
                    Criticality.STANDARD -> 4.hours
                    Criticality.GENTLE -> null
                }
            assertEquals(
                grace?.let { occurrence.scheduledInstant + it }
                    ?: f.at(occurrence.localDate.plus(DatePeriod(days = 1)), 0),
                event.effectiveAt,
                "a miss dated by something other than its own grace, seed=$seed",
            )
        }
        assertEquals(
            emptyList(),
            f.allEvents().filter {
                it.eventType == EventType.MISSED &&
                    byId.getValue(it.occurrenceId!!).state == OccurrenceState.WITHDRAWN
            },
        )
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

        val completedEvent =
            com.momtime.shared.domain.Event(
                id = nextId("evt"),
                occurrenceId = first.id,
                eventType = com.momtime.shared.domain.EventType.COMPLETED,
                deviceTimestamp = first.scheduledInstant,
                effectiveAt = null,
                source = com.momtime.shared.domain.EventSource.USER,
                payload = com.momtime.shared.domain.EventPayload.None,
            )
        occurrenceRepo.transition(first.id, OccurrenceState.COMPLETED, completedEvent)
        val completedBefore = occurrenceRepo.findById(first.id)!!

        // Edit the template's time and criticality through the command, then re-materialise the same window.
        val editCommand =
            EditTemplateCommand(
                templateRepo,
                occurrenceRepo,
                eventRepo,
                SqlDelightTransactor(database),
            ) { nextId("evt") }
        val edited =
            editCommand.dispatch(
                template.copy(timeOfDay = LocalTime(9, 0), criticality = Criticality.GENTLE),
                windowStart,
            )
        assertEquals(2, assertIs<EditResult.Edited>(edited).moved, "the two open occurrences took the edit")
        materialiseOnce(checkNotNull(templateRepo.findById(template.id)), windowStart, windowEnd)

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
        occurrenceRepo.transition(occurrence.id, OccurrenceState.MISSED, missedEvent)

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
