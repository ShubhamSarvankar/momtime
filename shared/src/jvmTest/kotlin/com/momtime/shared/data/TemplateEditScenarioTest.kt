package com.momtime.shared.data

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.engine.ArmCandidate
import com.momtime.shared.engine.ArmingSelection
import com.momtime.shared.engine.EscalationLadder
import com.momtime.shared.engine.EventLogReduction.AdherenceFigures
import com.momtime.shared.engine.OccurrenceAction
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Golden scenario 1 and the other behaviours of `EditTemplateCommand` that need a real database and a clock (ADR 0079),
 * each through the command, and each absence assertion followed by the `ReconcileCommand` that would produce the thing
 * if the code were wrong. A fixed clock on day D = 2026-03-10 in Asia/Kolkata (a Tuesday).
 */
class TemplateEditScenarioTest {
    private val f = TemplateEditFixture()
    private val day = LocalDate(2026, 3, 10)
    private val before = day.plus(DatePeriod(days = -1))
    private val after = day.plus(DatePeriod(days = 1))
    private val afterThat = day.plus(DatePeriod(days = 2))

    @AfterTest
    fun tearDown() = f.close()

    private fun open(templateId: String) = f.occurrences.findForTemplate(templateId).filter { !it.isTerminal }

    private fun row(
        templateId: String,
        date: LocalDate,
    ): Occurrence = f.occurrences.findForTemplate(templateId).single { it.localDate == date && !it.isTerminal }

    // --- scenario 1

    @Test
    fun `scenario 1 an edit of timeOfDay after some occurrences are terminal`() {
        val now = f.at(day, 10)
        val template = f.template("t")
        f.insertAndMaterialise(template, f.at(before, 0), f.at(day.plus(DatePeriod(days = 2)), 0))
        val d1 = f.occurrences.findForTemplate("t").single { it.localDate == before }
        val d = f.occurrences.findForTemplate("t").single { it.localDate == day }
        val d2 = f.occurrences.findForTemplate("t").single { it.localDate == after }
        assertEquals(OccurrenceState.PENDING, d2.state)
        assertEquals(ActionResult.Done, f.actions.dispatch(d1.id, OccurrenceAction.ACKNOWLEDGE, f.at(before, 8, 5)))
        f.fire(d.id, f.at(day, 8))
        val rowsBefore = f.allOccurrences().associateBy { it.id }
        val eventsBefore = f.allEvents()

        val result = f.edit.dispatch(template.copy(timeOfDay = LocalTime(14, 30)), now)

        val rowsAfter = f.allOccurrences().associateBy { it.id }
        // 1 and 2: D-1 (terminal) and D (come due) are equal in every column.
        assertEquals(rowsBefore.getValue(d1.id), rowsAfter.getValue(d1.id), "D-1 is unchanged")
        assertEquals(OccurrenceState.COMPLETED, rowsAfter.getValue(d1.id).state)
        assertEquals(rowsBefore.getValue(d.id), rowsAfter.getValue(d.id), "D has come due and is unchanged")
        // 3: D+1 moved to 14:30 and kept everything else.
        assertEquals(
            rowsBefore.getValue(d2.id).copy(scheduledInstant = f.at(after, 14, 30)),
            rowsAfter.getValue(d2.id),
            "D+1 moved to 14:30 and is otherwise the same row",
        )
        // 4: no event added, none removed, for any of them.
        assertEquals(eventsBefore, f.allEvents(), "the event table has exactly the rows it had")
        // 5: materialising D to D+3 adds D+2 only, at 14:30, with a slot above every earlier one.
        val created =
            f.occurrences.materialiseWindow(
                checkNotNull(f.templates.findById("t")),
                f.at(day, 0),
                f.at(day.plus(DatePeriod(days = 3)), 0),
                f.newId,
            )
        assertEquals(listOf(afterThat), created.map { it.localDate })
        assertEquals(f.at(afterThat, 14, 30), created.single().scheduledInstant)
        assertTrue(created.single().alarmSlot > rowsBefore.values.maxOf { it.alarmSlot }, "a slot above every earlier")
        // 6: the step that would miss it. At 13:00 on D+1 the old instant's grace (12:00) has ended, the new one's
        // (18:30) has not.
        val missedNow = f.reconcile.dispatch(f.at(after, 13))
        assertEquals(1, missedNow, "only D is missed")
        val missed = f.occurrences.findForTemplate("t").associateBy { it.localDate }
        assertEquals(OccurrenceState.MISSED, missed.getValue(day).state)
        val missEvent = f.events.findForOccurrence(d.id).single { it.eventType == EventType.MISSED }
        assertEquals(f.at(day, 12), missEvent.effectiveAt, "the end of D's own, unmoved grace")
        assertEquals(OccurrenceState.PENDING, missed.getValue(after).state, "D+1 is not missed because of the move")
        assertEquals(emptyList(), f.events.findForOccurrence(d2.id).filter { it.eventType == EventType.MISSED })
        assertEquals(OccurrenceState.COMPLETED, missed.getValue(before).state)
        assertEquals(AdherenceFigures(completed = 1, missed = 1, skipped = 0), f.figures(f.at(after, 13)))
        // Last, so that a failure above names the assertion of the plan that failed rather than this count.
        assertEquals(EditResult.Edited(withdrawn = emptyList(), moved = 1), result)
    }

    @Test
    fun `an edit to earlier than now keeps today's time`() {
        val now = f.at(day, 10)
        val template = f.template("t", time = LocalTime(12, 0))
        f.insertAndMaterialise(template, f.at(day, 0), f.at(day.plus(DatePeriod(days = 2)), 0))

        f.edit.dispatch(template.copy(timeOfDay = LocalTime(9, 0)), now)

        assertEquals(f.at(day, 12), row("t", day).scheduledInstant, "today's reminder stays at 12:00")
        assertEquals(f.at(after, 9), row("t", after).scheduledInstant, "tomorrow's takes 09:00")
    }

    // --- deactivation and removed dates

    @Test
    fun `deactivation withdraws every open occurrence, due or not`() {
        val template = f.template("t")
        f.insertAndMaterialise(template, f.at(before, 0), f.at(day.plus(DatePeriod(days = 2)), 0))
        val yesterday = row("t", before)
        val today = row("t", day)
        val tomorrow = row("t", after)
        f.actions.dispatch(yesterday.id, OccurrenceAction.ACKNOWLEDGE, f.at(before, 8, 5))
        f.actions.dispatch(today.id, OccurrenceAction.SNOOZE, f.at(day, 9))
        assertEquals(OccurrenceState.SNOOZED, f.occurrences.findById(today.id)?.state)
        val yesterdayBefore = checkNotNull(f.occurrences.findById(yesterday.id))

        val result = f.edit.dispatch(template.copy(active = false), f.at(day, 10))

        // The step that would write MISSED if a withdrawn occurrence were still open: after both graces. It comes
        // first, so that a mutation which leaves one open fails here, on what it would produce.
        val missed = f.reconcile.dispatch(f.at(after, 20))
        assertEquals(0, missed, "a withdrawn occurrence is never missed")
        assertEquals(emptyList(), f.allEvents().filter { it.eventType == EventType.MISSED })
        assertEquals(AdherenceFigures(completed = 1, missed = 0, skipped = 0), f.figures(f.at(after, 20)))
        for (id in listOf(today.id, tomorrow.id)) {
            assertEquals(OccurrenceState.WITHDRAWN, f.occurrences.findById(id)?.state)
            val withdrawal = f.events.findForOccurrence(id).single { it.eventType == EventType.WITHDRAWN }
            assertEquals(EventSource.USER, withdrawal.source)
            assertEquals(EventPayload.None, withdrawal.payload)
        }
        assertEquals(yesterdayBefore, f.occurrences.findById(yesterday.id), "yesterday is untouched")
        assertEquals(2, assertIs<EditResult.Edited>(result).withdrawn.size)
    }

    @Test
    fun `a recurrence change withdraws exactly the dates no longer wanted`() {
        val template = f.template("t")
        f.insertAndMaterialise(template, f.at(day, 0), f.at(day.plus(DatePeriod(days = 3)), 0))
        val wednesday = row("t", after)
        assertEquals(DayOfWeek.WEDNESDAY, after.dayOfWeek)

        val result =
            f.edit.dispatch(
                template.copy(recurrence = Recurrence.Weekly(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY))),
                f.at(day, 7),
            )
        // Captured before the Reconcile, which makes this row MISSED: what the edit left of the wanted date.
        val wantedAfterEdit = checkNotNull(f.occurrences.findById(wednesday.id))

        // The step that would miss a withdrawn date: after the grace of all three. It comes first, so that a mutation
        // which leaves an unwanted date open fails here, on the miss it produces.
        f.reconcile.dispatch(f.at(day.plus(DatePeriod(days = 3)), 0))
        val missed = f.allEvents().filter { it.eventType == EventType.MISSED }
        assertEquals(listOf(wednesday.id), missed.map { it.occurrenceId }, "only the wanted, unanswered date is missed")
        assertEquals(AdherenceFigures(0, 1, 0), f.figures(f.at(day.plus(DatePeriod(days = 3)), 0)))
        assertEquals(setOf(day, afterThat), assertIs<EditResult.Edited>(result).withdrawn.map { it.localDate }.toSet())
        assertEquals(
            setOf(OccurrenceState.WITHDRAWN),
            f.occurrences
                .findForTemplate("t")
                .filter { it.localDate != after }
                .map { it.state }
                .toSet(),
        )
        // Restored in the close out of #32: the wanted date's row was open after the edit, and is the row rule 5 leaves
        // it as (the time and the criticality are the template's own, so nothing about it changes).
        assertEquals(OccurrenceState.PENDING, wantedAfterEdit.state, "the wanted date is open after the edit")
        assertEquals(wednesday, wantedAfterEdit, "and is the row it was")
    }

    @Test
    fun `reactivation materialises fresh occurrences and never a past one`() {
        val a = f.template("a", time = LocalTime(9, 0))
        val b = f.template("b", time = LocalTime(20, 0))
        val span = f.at(day, 0) to f.at(day.plus(DatePeriod(days = 3)), 0)
        f.insertAndMaterialise(a, span.first, span.second)
        f.insertAndMaterialise(b, span.first, span.second)
        f.edit.dispatch(a.copy(active = false), f.at(day, 10))
        f.edit.dispatch(b.copy(active = false), f.at(day, 10))
        val withdrawn = f.allOccurrences()
        assertEquals(6, withdrawn.size)
        assertTrue(withdrawn.all { it.state == OccurrenceState.WITHDRAWN }, "setup: both templates are deactivated")
        val eventsBefore = f.allEvents()

        f.edit.dispatch(a, f.at(day, 10, 5))
        f.edit.dispatch(b, f.at(day, 10, 5))
        f.materialise.dispatch(f.at(day, 10, 5))

        val fresh = f.allOccurrences().filter { it.state != OccurrenceState.WITHDRAWN }

        fun dates(templateId: String) = fresh.filter { it.templateId == templateId }.map { it.localDate }.toSet()
        assertEquals(setOf(after, afterThat), dates("a"), "A: none for today's 09:00, which is past")
        assertEquals(setOf(day, after), dates("b"), "B: today's 20:00 and tomorrow")
        assertEquals(f.at(day, 20), fresh.single { it.templateId == "b" && it.localDate == day }.scheduledInstant)
        assertTrue(fresh.all { it.scheduledInstant >= f.at(day, 10, 5) }, "no new row is for an instant already past")
        assertTrue(fresh.map { it.id }.intersect(withdrawn.map { it.id }.toSet()).isEmpty(), "new ids")
        assertTrue(fresh.map { it.alarmSlot }.intersect(withdrawn.map { it.alarmSlot }.toSet()).isEmpty(), "new slots")
        val withdrawnIds = withdrawn.map { it.id }.toSet()
        assertEquals(
            withdrawn.toSet(),
            f.allOccurrences().filter { it.id in withdrawnIds }.toSet(),
            "six rows unchanged",
        )
        assertEquals(eventsBefore, f.allEvents().filter { e -> withdrawn.any { it.id == e.occurrenceId } }, "events")
    }

    // --- criticality is kept on the occurrence

    @Test
    fun `a criticality edit reaches only occurrences that have not come due`() {
        val template = f.template("t", time = LocalTime(8, 0), criticality = Criticality.CRITICAL)
        f.insertAndMaterialise(template, f.at(before, 0), f.at(day.plus(DatePeriod(days = 2)), 0))
        val overdue = row("t", before)
        val ringing = row("t", day)
        val tomorrow = row("t", after)
        f.fire(ringing.id, f.at(day, 8))

        f.edit.dispatch(template.copy(criticality = Criticality.GENTLE), f.at(day, 10))

        // No Reconcile ran, so the overdue one is still pending and open.
        assertEquals(OccurrenceState.PENDING, f.occurrences.findById(overdue.id)?.state)
        assertEquals(Criticality.CRITICAL, f.occurrences.findById(overdue.id)?.criticality)
        assertEquals(Criticality.CRITICAL, f.occurrences.findById(ringing.id)?.criticality)
        assertEquals(Criticality.GENTLE, f.occurrences.findById(tomorrow.id)?.criticality)
        val ringingNow = checkNotNull(f.occurrences.findById(ringing.id))
        val channels = setOf(Channel.RING, Channel.RING_REPEAT)
        val next = ArmingSelection.next(listOf(ArmCandidate(ringingNow, firedCount = 1)), channels)
        assertEquals(
            EscalationLadder.forOccurrence(ringing.scheduledInstant, Criticality.CRITICAL)[1],
            next?.rung,
            "the ringing one's next rung is still the CRITICAL repeat",
        )
        assertEquals(ringing.scheduledInstant + 5.minutes, next?.rung?.instant)
    }

    @Test
    fun `a criticality edit never ends a grace in the past`() {
        val template = f.template("t", criticality = Criticality.GENTLE)
        f.insertAndMaterialise(template, f.at(day, 0), f.at(after, 0))
        val occurrence = row("t", day)

        f.edit.dispatch(template.copy(criticality = Criticality.CRITICAL), f.at(day, 15))

        // CRITICAL's two hour grace from 08:00 ended at 10:00; the step that would miss it at once.
        assertEquals(0, f.reconcile.dispatch(f.at(day, 15, 1)))
        assertEquals(emptyList(), f.events.findForOccurrence(occurrence.id).filter { it.eventType == EventType.MISSED })
        assertEquals(OccurrenceState.PENDING, f.occurrences.findById(occurrence.id)?.state)
        f.reconcile.dispatch(f.at(after, 0, 1))
        val missed = f.events.findForOccurrence(occurrence.id).single { it.eventType == EventType.MISSED }
        assertEquals(f.at(after, 0), missed.effectiveAt, "the end of her local day, Gentle's grace")
    }

    @Test
    fun `an edit of a snoozed occurrence changes nothing of it`() {
        val template = f.template("t", criticality = Criticality.CRITICAL)
        f.insertAndMaterialise(template, f.at(day, 0), f.at(after, 0))
        val snoozed = row("t", day)
        f.actions.dispatch(snoozed.id, OccurrenceAction.SNOOZE, f.at(day, 9, 50))
        val rowBefore = checkNotNull(f.occurrences.findById(snoozed.id))
        val eventsBefore = f.events.findForOccurrence(snoozed.id)
        assertEquals(OccurrenceState.SNOOZED, rowBefore.state)

        val result =
            f.edit.dispatch(
                template.copy(timeOfDay = LocalTime(20, 0), criticality = Criticality.GENTLE),
                f.at(day, 9, 55),
            )

        assertEquals(EditResult.Edited(withdrawn = emptyList(), moved = 0), result)
        assertEquals(rowBefore, f.occurrences.findById(snoozed.id))
        assertEquals(eventsBefore, f.events.findForOccurrence(snoozed.id))
        assertNotEquals(Criticality.GENTLE, f.occurrences.findById(snoozed.id)?.criticality)
    }

    // A snooze is not an instant either: with the device clock set back, a snoozed occurrence can have an instant
    // ahead of now, and the edit must still leave it alone (ADR 0079 item 2: "or its state is `SNOOZED`").
    @Test
    fun `a snoozed occurrence is untouched even when its instant is ahead of now`() {
        val template = f.template("t", criticality = Criticality.CRITICAL)
        f.insertAndMaterialise(template, f.at(day, 0), f.at(after, 0))
        val snoozed = row("t", day)
        f.actions.dispatch(snoozed.id, OccurrenceAction.SNOOZE, f.at(day, 9, 50))
        val rowBefore = checkNotNull(f.occurrences.findById(snoozed.id))
        val eventsBefore = f.events.findForOccurrence(snoozed.id)
        assertEquals(OccurrenceState.SNOOZED, rowBefore.state)

        // The clock reads 07:00 on D again: 08:00 is ahead of now, and nothing but the state says it has come due.
        val result =
            f.edit.dispatch(
                template.copy(timeOfDay = LocalTime(20, 0), criticality = Criticality.GENTLE),
                f.at(day, 7),
            )

        assertEquals(rowBefore, f.occurrences.findById(snoozed.id))
        assertEquals(eventsBefore, f.events.findForOccurrence(snoozed.id))
        assertEquals(EditResult.Edited(withdrawn = emptyList(), moved = 0), result)
    }
}
