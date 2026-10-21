package com.momtime.shared.engine

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Servings per nutrition tag per scheduled date, a reduction over the log (invariant 4, ADR 0086). Counts only: no
 * quantity, no target, no percentage of anything (CLAUDE.md).
 *
 * An occurrence counts if its outcome as of `asOf` is completed under ADR 0040's rule, which is
 * [EventLogReduction.deciding] and not a copy of it, and it counts once under each tag of the completion event that
 * won. The tags are the ones recorded on that event when she completed it, so editing a template's tags never
 * changes what she already did. A completion that carries none, or no completion payload, counts under no tag. It is
 * attributed to the occurrence's scheduled `localDate`, never to the date of the event, so a backfill entered a day
 * later counts on the day it was for, from when it is entered. `MISSION_VERIFIED`, `MISSION_BYPASSED`, skips, misses
 * and withdrawals are not completions (ADR 0086 item 3). Only dates and tags with a count appear in the result.
 */
object NutritionReduction {
    fun servings(
        occurrences: List<Occurrence>,
        events: List<Event>,
        asOf: Instant,
    ): Map<LocalDate, Map<NutritionTag, Int>> {
        val deciding = EventLogReduction.deciding(events, asOf)
        val servings = HashMap<LocalDate, MutableMap<NutritionTag, Int>>()
        for (occurrence in occurrences) {
            val counted = deciding[occurrence.id] ?: continue
            if (counted.outcome != EventLogReduction.Outcome.COMPLETED) continue
            val payload = counted.event.payload
            val tags = if (payload is EventPayload.Completion) payload.nutritionTags else emptySet()
            for (tag in tags) {
                val day = servings.getOrPut(occurrence.localDate) { HashMap() }
                day[tag] = (day[tag] ?: 0) + 1
            }
        }
        return servings
    }
}
