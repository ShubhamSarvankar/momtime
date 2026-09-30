package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class EventLogReductionTest {
    private val zone = TimeZone.of("Asia/Kolkata")

    private fun occ(
        state: OccurrenceState,
        date: LocalDate = LocalDate(2026, 1, 1),
    ) = Occurrence(
        id = "id-${state.name}-$date",
        templateId = "tmpl",
        localDate = date,
        scheduledInstant = Instant.fromEpochMilliseconds(0),
        timeZoneId = zone,
        state = state,
        alarmSlot = 1,
    )

    @Test
    fun `adherence figures report three separate counts, never one collapsed percentage`() {
        val occurrences =
            listOf(
                occ(OccurrenceState.COMPLETED),
                occ(OccurrenceState.COMPLETED),
                occ(OccurrenceState.MISSED),
                occ(OccurrenceState.SKIPPED),
                occ(OccurrenceState.PENDING),
            )
        val figures = EventLogReduction.adherenceFigures(occurrences)
        assertEquals(EventLogReduction.AdherenceFigures(completed = 2, missed = 1, skipped = 1), figures)
    }

    @Test
    fun `a day with no critical occurrences does not count toward the 30-day metric`() {
        val date = LocalDate(2026, 1, 1)
        val occurrencesByDate = mapOf(date to listOf(occ(OccurrenceState.COMPLETED, date)))
        val days =
            EventLogReduction.criticalCompletionDays(
                occurrencesByDate = occurrencesByDate,
                criticalityOf = { Criticality.STANDARD },
                windowDates = listOf(date),
            )
        assertEquals(0, days)
    }

    @Test
    fun `a day counts only when every critical occurrence that day is completed`() {
        val date = LocalDate(2026, 1, 1)
        val allCompleted =
            mapOf(
                date to listOf(occ(OccurrenceState.COMPLETED, date), occ(OccurrenceState.COMPLETED, date)),
            )
        val oneMissed = mapOf(date to listOf(occ(OccurrenceState.COMPLETED, date), occ(OccurrenceState.MISSED, date)))

        val daysAllCompleted =
            EventLogReduction.criticalCompletionDays(allCompleted, { Criticality.CRITICAL }, listOf(date))
        val daysOneMissed =
            EventLogReduction.criticalCompletionDays(oneMissed, { Criticality.CRITICAL }, listOf(date))

        assertEquals(1, daysAllCompleted)
        assertEquals(0, daysOneMissed)
    }
}
