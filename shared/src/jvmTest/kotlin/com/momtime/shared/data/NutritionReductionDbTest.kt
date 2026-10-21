package com.momtime.shared.data

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.EventLogReduction
import com.momtime.shared.engine.NutritionReduction
import com.momtime.shared.engine.OccurrenceAction
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The nutrition reduction over the real repositories and commands (ADR 0086). Every completion is written by
 * `OccurrenceActionCommand`, which records the template's tags inside its transaction; every template edit by
 * `EditTemplateCommand`. A backfill is written straight through `EventRepository` as a fixture, because
 * `BackfillCommand` arrives in PR 11: the reduction reads the log, not the command that wrote it.
 */
class NutritionReductionDbTest {
    private val day1 = LocalDate(2026, 3, 1)
    private val day2 = day1.plus(1, DateTimeUnit.DAY)

    @Test
    fun `editing a template's tags does not change a past count`() {
        val f = TemplateEditFixture()
        try {
            f.insertAndMaterialise(f.template("t"), f.at(day1, 0), f.at(day1.plus(2, DateTimeUnit.DAY), 0))
            val (first, second) = f.occurrences.findForTemplate("t").sortedBy { it.scheduledInstant }
            assertEquals(
                ActionResult.Done,
                f.actions.dispatch(first.id, OccurrenceAction.ACKNOWLEDGE, f.at(day1, 8, 5)),
            )

            val edited = checkNotNull(f.templates.findById("t")).copy(nutritionTags = setOf(NutritionTag.CALCIUM))
            assertTrue(f.edit.dispatch(edited, f.at(day1, 20)) is EditResult.Edited)

            assertEquals(
                ActionResult.Done,
                f.actions.dispatch(second.id, OccurrenceAction.ACKNOWLEDGE, f.at(day2, 8, 5)),
            )
            assertEquals(
                mapOf(
                    day1 to mapOf(NutritionTag.IRON to 1),
                    day2 to mapOf(NutritionTag.CALCIUM to 1),
                ),
                NutritionReduction.servings(f.allOccurrences(), f.allEvents(), f.at(day2, 9)),
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun `a backfill counts on its scheduled date from when it is entered`() {
        val f = TemplateEditFixture()
        try {
            f.insertAndMaterialise(f.template("t"), f.at(day1, 0), f.at(day2, 0))
            val occurrence = f.occurrences.findForTemplate("t").single()
            assertEquals(1, f.reconcile.dispatch(f.at(day2, 8)), "the reminder was missed")
            assertEquals(OccurrenceState.MISSED, checkNotNull(f.occurrences.findById(occurrence.id)).state)

            val enteredAt = f.at(day2, 10)
            f.events.insert(
                Event(
                    id = f.newId(),
                    occurrenceId = occurrence.id,
                    eventType = EventType.COMPLETED_BACKFILLED,
                    deviceTimestamp = enteredAt,
                    effectiveAt = null,
                    source = EventSource.USER,
                    payload = EventPayload.Completion(setOf(NutritionTag.IRON)),
                ),
            )
            val occurrences = f.allOccurrences()
            val events = f.allEvents()
            // The backfill is in the log when asOf is before it: the step that would count it early.
            assertTrue(events.any { it.eventType == EventType.COMPLETED_BACKFILLED })

            assertEquals(
                emptyMap(),
                NutritionReduction.servings(occurrences, events, enteredAt - 1.minutes),
                "nothing counts before it is entered",
            )
            assertEquals(
                mapOf(day1 to mapOf(NutritionTag.IRON to 1)),
                NutritionReduction.servings(occurrences, events, enteredAt),
                "it counts on the scheduled date, from when it is entered (inclusive)",
            )
        } finally {
            f.close()
        }
    }

    private class Reach {
        var backfilledTagged = 0
        var backfilledUntagged = 0
        var strictlyFewer = 0
        var comparisons = 0
    }

    /**
     * One random history through the production commands: some reminders acknowledged, some skipped, some missed, and
     * of the missed some backfilled later, with a tag and without. Then, as of the end and as of a random instant
     * between, nutrition is compared date by date with adherence's completed figure for the same occurrences. Every
     * template has at most one tag, so the number of servings of a date is the number of occurrences counted under a tag.
     */
    private fun run(
        seed: Int,
        allTagged: Boolean,
        reach: Reach,
    ) {
        val f = TemplateEditFixture()
        try {
            val random = Random(seed)
            val start = f.at(day1, 0)
            val end = f.at(day1.plus(8, DateTimeUnit.DAY), 0)
            for (i in 0 until 3) {
                val tags =
                    if (allTagged || random.nextBoolean()) {
                        setOf(NutritionTag.entries[random.nextInt(NutritionTag.entries.size)])
                    } else {
                        emptySet()
                    }
                f.insertAndMaterialise(
                    f.template("t$i", time = LocalTime(6 + 5 * i, 0)).copy(nutritionTags = tags),
                    start,
                    end,
                )
            }
            val toBackfill = mutableListOf<String>()
            for (occurrence in f.allOccurrences().sortedBy { it.scheduledInstant }) {
                when (random.nextInt(4)) {
                    0 ->
                        f.actions.dispatch(
                            occurrence.id,
                            OccurrenceAction.ACKNOWLEDGE,
                            occurrence.scheduledInstant + (1 + random.nextInt(30)).minutes,
                        )
                    1 ->
                        f.actions.dispatch(
                            occurrence.id,
                            OccurrenceAction.SKIP,
                            occurrence.scheduledInstant + (1 + random.nextInt(30)).minutes,
                        )
                    2 -> Unit
                    else -> toBackfill += occurrence.id
                }
            }
            f.reconcile.dispatch(end + 3.days)
            for (id in toBackfill) {
                val occurrence = checkNotNull(f.occurrences.findById(id))
                check(occurrence.state == OccurrenceState.MISSED) { "the occurrence to backfill was missed" }
                val missedAt =
                    checkNotNull(
                        f.events
                            .findByOccurrenceAndType(id, EventType.MISSED)
                            .single()
                            .effectiveAt,
                    )
                val tags = checkNotNull(f.templates.findById(occurrence.templateId)).nutritionTags
                if (tags.isEmpty()) reach.backfilledUntagged++ else reach.backfilledTagged++
                f.events.insert(
                    Event(
                        id = f.newId(),
                        occurrenceId = id,
                        eventType = EventType.COMPLETED_BACKFILLED,
                        deviceTimestamp = missedAt + (1 + random.nextInt(40)).hours,
                        effectiveAt = null,
                        source = EventSource.USER,
                        payload = EventPayload.Completion(tags),
                    ),
                )
            }

            val occurrences = f.allOccurrences()
            val events = f.allEvents()
            val last = events.maxOf { it.effectiveAt ?: it.deviceTimestamp }
            val middle = start + random.nextLong(0, (last - start).inWholeMinutes).minutes
            for (asOf in listOf(last, middle)) {
                val servings = NutritionReduction.servings(occurrences, events, asOf)
                for ((date, group) in occurrences.groupBy { it.localDate }) {
                    val completed = EventLogReduction.adherenceFigures(group, events, asOf).completed
                    val counted = servings[date].orEmpty().values.sum()
                    assertTrue(
                        counted <= completed,
                        "seed=$seed asOf=$asOf $date: $counted servings, $completed completed",
                    )
                    if (allTagged) {
                        assertEquals(
                            completed,
                            counted,
                            "seed=$seed asOf=$asOf $date: every completion has a tag",
                        )
                    }
                    reach.comparisons++
                    if (counted < completed) reach.strictlyFewer++
                }
            }
        } finally {
            f.close()
        }
    }

    @Test
    fun `nutrition agrees with adherence`() {
        val tagged = Reach()
        val mixed = Reach()
        for (seed in 1..25) {
            run(seed, allTagged = true, reach = tagged)
            run(seed, allTagged = false, reach = mixed)
        }
        // The generator reaches the cases that tell one outcome rule from another: a miss that is later backfilled, with
        // and without tags, and a date on which nutrition counts fewer than adherence completed.
        assertTrue(tagged.backfilledTagged > 0, "no missed occurrence with tags was later backfilled in 25 seeds")
        assertTrue(
            mixed.backfilledTagged > 0,
            "no missed occurrence with tags was later backfilled in 25 seeds (mixed)",
        )
        assertTrue(mixed.backfilledUntagged > 0, "no missed occurrence without tags was later backfilled in 25 seeds")
        assertTrue(
            mixed.strictlyFewer > 0,
            "nutrition never counted fewer than adherence, so `at most` was never tested",
        )
        assertTrue(tagged.comparisons > 100 && mixed.comparisons > 100, "too few dates compared")
    }
}
