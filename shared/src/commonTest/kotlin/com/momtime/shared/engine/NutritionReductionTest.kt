package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionResultType
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The pure half of the nutrition reduction's tests (ADR 0086 items 2 and 3): subject and setup are events and
 * occurrences, the entry point is `NutritionReduction.servings`. The half that needs the commands and the database
 * (a template edit between two completions, a backfill, the agreement with adherence) is `NutritionReductionDbTest`.
 */
class NutritionReductionTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val base = Instant.parse("2026-03-01T02:30:00Z") // 08:00 on 1 March in Asia/Kolkata
    private val farFuture = base + 3650.days
    private var seq = 0

    private fun occ(scheduled: Instant) =
        Occurrence(
            id = "occ-${seq++}",
            templateId = "tmpl",
            localDate = scheduled.toLocalDateTime(zone).date,
            scheduledInstant = scheduled,
            timeZoneId = zone,
            state = OccurrenceState.COMPLETED,
            alarmSlot = seq,
            criticality = Criticality.STANDARD,
        )

    private fun event(
        occurrence: Occurrence,
        type: EventType,
        at: Instant,
        payload: EventPayload = EventPayload.None,
        effectiveAt: Instant? = null,
    ) = Event("evt-${seq++}", occurrence.id, type, at, effectiveAt, EventSource.USER, payload)

    private fun completed(
        occurrence: Occurrence,
        at: Instant,
        vararg tags: NutritionTag,
    ) = event(occurrence, EventType.COMPLETED, at, EventPayload.Completion(tags.toSet()))

    private val day1 = LocalDate(2026, 3, 1)
    private val day2 = LocalDate(2026, 3, 2)

    @Test
    fun `counts servings per tag per scheduled date`() {
        val a = occ(base)
        val b = occ(base + 1.hours)
        val c = occ(base + 1.days)
        val events =
            listOf(
                completed(a, base, NutritionTag.IRON, NutritionTag.CALCIUM),
                completed(b, base + 1.hours, NutritionTag.IRON),
                completed(c, base + 1.days, NutritionTag.CALCIUM, NutritionTag.FRUIT),
            )
        assertEquals(
            mapOf(
                day1 to mapOf(NutritionTag.IRON to 2, NutritionTag.CALCIUM to 1),
                day2 to mapOf(NutritionTag.CALCIUM to 1, NutritionTag.FRUIT to 1),
            ),
            NutritionReduction.servings(listOf(a, b, c), events, farFuture),
        )
    }

    // The probes sit on completed occurrences, after the completion, because a count that is wrongly added for them
    // would carry no tags and so would not show; what shows is a probe displacing the completion that has the tags.
    @Test
    fun `mission events, skips, misses and withdrawals count nothing`() {
        val mission = occ(base)
        val withdrawnAfter = occ(base)
        val skippedAfter = occ(base)
        val missedAfter = occ(base)
        val skipped = occ(base)
        val missed = occ(base)
        val withdrawn = occ(base)
        val events =
            listOf(
                // The one completion that counts, with a verified and a bypassed mission after it.
                completed(mission, base, NutritionTag.IRON, NutritionTag.CALCIUM),
                event(
                    mission,
                    EventType.MISSION_VERIFIED,
                    base + 1.hours,
                    EventPayload.MissionResult(MissionResultType.BARCODE),
                ),
                event(
                    mission,
                    EventType.MISSION_BYPASSED,
                    base + 2.hours,
                    EventPayload.MissionResult(MissionResultType.BARCODE),
                ),
                // A completion with a withdrawal after it: a withdrawal is not an outcome, so the completion stands.
                completed(withdrawnAfter, base, NutritionTag.FRUIT),
                event(withdrawnAfter, EventType.WITHDRAWN, base + 1.hours),
                // A completion with a later skip or a later miss: the later outcome wins, and neither carries a tag.
                completed(skippedAfter, base, NutritionTag.PROTEIN),
                event(skippedAfter, EventType.SKIPPED, base + 1.hours),
                completed(missedAfter, base, NutritionTag.DAIRY),
                event(missedAfter, EventType.MISSED, base + 1.hours, effectiveAt = base + 1.hours),
                // Occurrences with nothing but one such event.
                event(skipped, EventType.SKIPPED, base),
                event(missed, EventType.MISSED, base, effectiveAt = base),
                event(withdrawn, EventType.WITHDRAWN, base),
            )
        val all = listOf(mission, withdrawnAfter, skippedAfter, missedAfter, skipped, missed, withdrawn)
        assertEquals(
            mapOf(day1 to mapOf(NutritionTag.IRON to 1, NutritionTag.CALCIUM to 1, NutritionTag.FRUIT to 1)),
            NutritionReduction.servings(all, events, farFuture),
        )
    }

    // Not in the plan; for review. The outcome decides, not the payload: a skip or a miss that carried a completion
    // payload (no writer produces one, and the repository's column guard refuses it) counts nothing. The completion
    // beside them is the positive control, and the two others are the step that would count them if the code were wrong.
    @Test
    fun `the outcome decides what counts, not the payload of the event that decided it`() {
        val skipped = occ(base)
        val missed = occ(base)
        val completed = occ(base)
        val events =
            listOf(
                event(skipped, EventType.SKIPPED, base, EventPayload.Completion(setOf(NutritionTag.IRON))),
                event(missed, EventType.MISSED, base, EventPayload.Completion(setOf(NutritionTag.CALCIUM)), base),
                completed(completed, base, NutritionTag.FRUIT),
            )
        assertEquals(
            mapOf(day1 to mapOf(NutritionTag.FRUIT to 1)),
            NutritionReduction.servings(listOf(skipped, missed, completed), events, farFuture),
        )
    }

    // Not in the plan; for review. ADR 0086 item 1: a completion with no tags, or with no completion payload at all,
    // counts under no tag. The completion with tags beside it is the positive control.
    @Test
    fun `a completion with no tags counts under no tag`() {
        val tagged = occ(base)
        val untagged = occ(base)
        val noPayload = occ(base)
        val events =
            listOf(
                completed(tagged, base, NutritionTag.SUPPLEMENT),
                completed(untagged, base),
                event(noPayload, EventType.COMPLETED, base),
            )
        assertEquals(
            mapOf(day1 to mapOf(NutritionTag.SUPPLEMENT to 1)),
            NutritionReduction.servings(listOf(tagged, untagged, noPayload), events, farFuture),
        )
    }
}
