package com.momtime.shared.data

import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.TaskType
import com.momtime.shared.engine.TemplateRefusal
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The rest of `EditTemplateCommand` (ADR 0079): the fields that touch no occurrence, validation, one transaction. */
class EditTemplateCommandTest {
    private var f = TemplateEditFixture()
    private val day = LocalDate(2026, 3, 10)

    @AfterTest
    fun tearDown() = f.close()

    private fun seedThree(templateId: String = "t") {
        f.insertAndMaterialise(f.template(templateId), f.at(day, 0), f.at(day.plus(DatePeriod(days = 3)), 0))
    }

    @Test
    fun `other fields touch no occurrence`() {
        seedThree()
        val template = checkNotNull(f.templates.findById("t"))
        val rowsBefore = f.allOccurrences()
        val eventsBefore = f.allEvents()

        val result =
            f.edit.dispatch(
                template.copy(
                    title = "Folic acid",
                    notes = "with food",
                    taskType = TaskType.MEDICINE,
                    dosage = "400 mcg",
                    doctorInstructions = "as directed",
                    nutritionTags = setOf(NutritionTag.IRON, NutritionTag.CALCIUM),
                    inventoryCount = 30,
                    refillThresholdDays = 5,
                ),
                f.at(day, 7),
            )

        assertEquals(EditResult.Edited(withdrawn = emptyList(), moved = 0), result)
        assertEquals(rowsBefore, f.allOccurrences(), "no occurrence row changed")
        assertEquals(eventsBefore, f.allEvents(), "no event changed")
        val stored = checkNotNull(f.templates.findById("t"))
        assertEquals("Folic acid", stored.title)
        assertEquals(setOf(NutritionTag.IRON, NutritionTag.CALCIUM), stored.nutritionTags)
        assertEquals(30, stored.inventoryCount)
        // The positive control: a change of time moves the rows, so the assertions above can fail.
        f.edit.dispatch(stored.copy(timeOfDay = LocalTime(9, 0)), f.at(day, 7))
        assertNotEquals(rowsBefore, f.allOccurrences())
    }

    @Test
    fun `validation refuses and writes nothing`() {
        seedThree()
        val valid = checkNotNull(f.templates.findById("t"))
        val rowsBefore = f.allOccurrences()
        val eventsBefore = f.allEvents()
        val cases =
            mapOf(
                "an empty title" to (valid.copy(title = "") to TemplateRefusal.TITLE_LENGTH),
                "a title of 25 characters" to (valid.copy(title = "x".repeat(25)) to TemplateRefusal.TITLE_LENGTH),
                "an interval of 0" to
                    (valid.copy(recurrence = Recurrence.EveryNDays(0, day)) to TemplateRefusal.INTERVAL_BELOW_ONE),
                "no weekday" to (valid.copy(recurrence = Recurrence.Weekly(emptySet())) to TemplateRefusal.NO_WEEKDAYS),
            )
        for ((name, case) in cases) {
            val (invalid, reason) = case
            val result = runCatching { f.edit.dispatch(invalid, f.at(day, 7)) }.getOrNull()
            assertEquals(EditResult.Refused(reason), result, "$name is refused")
            assertEquals(valid, f.templates.findById("t"), "$name leaves the template row as it was")
            assertEquals(rowsBefore, f.allOccurrences(), "$name touches no occurrence")
            assertEquals(eventsBefore, f.allEvents(), "$name writes no event")
        }
        // And a title of 1 and of 24 characters is accepted, so the bounds are the ones stated.
        for (title in listOf("x", "x".repeat(24))) {
            assertIs<EditResult.Edited>(f.edit.dispatch(valid.copy(title = title), f.at(day, 7)), title)
        }
        // Creation applies the same checks and inserts nothing.
        val fresh = f.template("new", recurrence = Recurrence.Weekly(emptySet()))
        assertEquals(CreateResult.Refused(TemplateRefusal.NO_WEEKDAYS), f.create.dispatch(fresh))
        assertEquals(null, f.templates.findById("new"))
        assertEquals(
            CreateResult.Refused(TemplateRefusal.TITLE_LENGTH),
            f.create.dispatch(f.template("n2").copy(title = "")),
        )
        assertEquals(
            CreateResult.Refused(TemplateRefusal.INTERVAL_BELOW_ONE),
            f.create.dispatch(f.template("n3", recurrence = Recurrence.EveryNDays(0, day))),
        )
        assertEquals(null, f.templates.findById("n2"))
        assertEquals(null, f.templates.findById("n3"))
        assertEquals(CreateResult.Created, f.create.dispatch(f.template("ok")))
        assertEquals(f.template("ok"), f.templates.findById("ok"))
    }

    @Test
    fun `doctor instructions are stored verbatim`() {
        val text = "\n  Take 2 tablets (500 mg) twice a day.\r\nदिन में दो बार, खाने के बाद 12.5 mg \n\n"
        f.create.dispatch(f.template("t").copy(doctorInstructions = text))
        assertContentEquals(
            text.encodeToByteArray(),
            f.templates
                .findById("t")
                ?.doctorInstructions
                ?.encodeToByteArray(),
        )

        val other = "7 days:\t1 tab / 5 ml  "
        f.edit.dispatch(checkNotNull(f.templates.findById("t")).copy(doctorInstructions = other), f.at(day, 7))
        assertContentEquals(
            other.encodeToByteArray(),
            f.templates
                .findById("t")
                ?.doctorInstructions
                ?.encodeToByteArray(),
        )
    }

    @Test
    fun `a failure part way through an edit changes nothing`() {
        f.close()
        var applied = 0
        f =
            TemplateEditFixture { real ->
                object : OccurrenceRepository by real {
                    override fun applyEdit(
                        occurrenceId: String,
                        scheduledInstant: kotlin.time.Instant,
                        criticality: com.momtime.shared.domain.Criticality,
                    ) {
                        check(++applied < 2) { "the second occurrence write fails" }
                        real.applyEdit(occurrenceId, scheduledInstant, criticality)
                    }
                }
            }
        seedThree()
        val template = checkNotNull(f.templates.findById("t"))
        val rowsBefore = f.allOccurrences()
        val eventsBefore = f.allEvents()
        assertEquals(3, rowsBefore.count { it.state == OccurrenceState.PENDING })

        // Three rows are not yet due at 07:00 on D: the edit moves three, and the second write throws.
        assertFailsWith<IllegalStateException> {
            f.edit.dispatch(template.copy(title = "Changed", timeOfDay = LocalTime(14, 30)), f.at(day, 7))
        }

        assertEquals(2, applied, "the seam threw on the second occurrence write, after the first had been made")
        assertEquals(template, f.templates.findById("t"), "the template row is unchanged")
        assertEquals(rowsBefore, f.allOccurrences(), "no occurrence row changed, the first move included")
        assertEquals(eventsBefore, f.allEvents(), "the event table is unchanged")
    }

    // The invariant of ADR 0079 item 3 over random edits: no open occurrence belongs to an inactive template.
    @Test
    fun `after any edit no open occurrence belongs to an inactive template`() {
        for (seed in 1..20) {
            f.close()
            f = TemplateEditFixture()
            val steps = EditSteps(f, Random(seed), "t")
            repeat(40) { step ->
                steps.step()
                val inactive =
                    f.templates
                        .findAll()
                        .filter { !it.active }
                        .map { it.id }
                        .toSet()
                val offenders: List<Occurrence> = f.occurrences.findOpen().filter { it.templateId in inactive }
                assertEquals(
                    emptyList(),
                    offenders,
                    "an open occurrence of an inactive template, seed=$seed step=$step",
                )
            }
            assertTrue(steps.edits > 0)
        }
    }
}
