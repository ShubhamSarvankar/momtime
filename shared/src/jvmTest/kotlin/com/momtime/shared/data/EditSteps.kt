package com.momtime.shared.data

import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.engine.OccurrenceAction
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The generator of scenario 12 (ADR 0079): one template, and steps that are each a real operation through a production
 * command. A fixed span of [SPAN_DAYS] days from [START]; the clock only moves forward and never past the span's end.
 * It counts what it reached, so a test can fail if the distinguishing cases were never produced.
 */
internal class EditSteps(
    private val f: TemplateEditFixture,
    private val random: Random,
    val templateId: String,
) {
    var now: Instant = START
        private set

    /** Edits of any kind dispatched. */
    var edits = 0
        private set

    /** Edits that moved at least one open occurrence. */
    var editsThatMoved = 0
        private set

    /** Edits that withdrew at least one occurrence. */
    var editsThatWithdrew = 0
        private set

    /** Reactivations after which the materialisation created at least one occurrence. */
    var reactivationsThatCreated = 0
        private set

    init {
        f.templates.insert(f.template(templateId))
    }

    private fun current() = checkNotNull(f.templates.findById(templateId))

    private fun edit(edited: ScheduleTemplate): EditResult.Edited {
        edits++
        val result = f.edit.dispatch(edited, now)
        check(result is EditResult.Edited) { "the generator only makes valid edits: $result" }
        if (result.moved > 0) editsThatMoved++
        if (result.withdrawn.isNotEmpty()) editsThatWithdrew++
        return result
    }

    fun step() {
        when (random.nextInt(7)) {
            0 -> materialiseWindow()
            1 -> now = minOf(now + random.nextLong(0, 36 * 60 + 1).minutes, END)
            2 -> edit(current().copy(timeOfDay = LocalTime(random.nextInt(0, 24), 15 * random.nextInt(0, 4))))
            3 -> edit(current().copy(recurrence = randomRecurrence()))
            4 -> edit(current().copy(active = false))
            5 -> reactivate()
            else -> completeOne()
        }
    }

    private fun materialiseWindow() {
        val start = START + random.nextLong(0, SPAN_DAYS * 24L).hours
        val end = minOf(start + random.nextLong(1, 10 * 24L).hours, END)
        f.occurrences.materialiseWindow(current(), start, end, f.newId)
    }

    private fun reactivate() {
        edit(current().copy(active = true))
        if (f.materialise.dispatch(now) > 0) reactivationsThatCreated++
    }

    private fun completeOne() {
        val open = f.occurrences.findOpen().filter { it.templateId == templateId }
        if (open.isEmpty()) return
        f.actions.dispatch(open[random.nextInt(open.size)].id, OccurrenceAction.ACKNOWLEDGE, now)
    }

    private fun randomRecurrence(): Recurrence =
        when (random.nextInt(3)) {
            0 -> Recurrence.Daily
            1 -> {
                val days =
                    DayOfWeek.entries
                        .shuffled(random)
                        .take(random.nextInt(1, 4))
                        .toSet()
                Recurrence.Weekly(days)
            }
            else ->
                Recurrence.EveryNDays(
                    random.nextInt(1, 5),
                    START_DATE.plus(DatePeriod(days = random.nextInt(0, 20))),
                )
        }

    companion object {
        const val SPAN_DAYS = 20
        val START_DATE = LocalDate(2026, 3, 1)
        val START: Instant = Instant.parse("2026-02-28T18:30:00Z") // 2026-03-01 00:00 in Asia/Kolkata
        val END: Instant = START + (SPAN_DAYS * 24L).hours
    }
}
