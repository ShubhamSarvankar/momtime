package com.momtime.android.debug

import android.content.ComponentName
import android.content.Context
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.MutableClock
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Recurrence
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The debug only seed (ADR 0072): a CRITICAL test reminder three to four minutes out and three daily STANDARD
 * reminders, made through the domain's repositories and commands, then materialised and armed, so that exactly one
 * alarm is armed. This test lives in the `debug` unit test source set with the code it tests, which is not in a release
 * build.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class SeederTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-03-01T06:00:30Z")
    private val fixture = ArmingFixture(context, clock = MutableClock(now), seedPregnancy = false)
    private var nextId = 0

    @After
    fun tearDown() = fixture.close()

    private fun seeder() =
        Seeder(
            fixture.graph.get<PregnancyRepository>(),
            fixture.graph.get<ScheduleTemplateRepository>(),
            fixture.graph.get<OccurrenceRepository>(),
            fixture.graph.get<MaterialiseCommand>(),
            fixture.coordinator,
            fixture.clock,
            { TimeZone.UTC },
            { "seed-${nextId++}" },
        )

    private val templates: ScheduleTemplateRepository get() = fixture.graph.get()

    @Test
    fun `seeding creates the test reminder and three daily reminders through the repositories`() {
        val result = seeder().seed()

        // Two days of three daily reminders from 06:00 (08:00, 13:00, 20:00 twice), and the one test reminder.
        assertEquals(SeedResult.Seeded(7), result)
        val open = fixture.occurrences.findOpen()
        assertEquals(7, open.size)
        assertEquals("every occurrence has its own alarm slot", 7, open.map { it.alarmSlot }.toSet().size)

        val test = checkNotNull(templates.findById(Seeder.TEST_ID))
        assertEquals(Criticality.CRITICAL, test.criticality)
        assertTrue("active, as every reminder is (ADR 0079 item 11)", test.active)
        val dueDate =
            fixture.occurrences
                .findOpen()
                .single { it.templateId == Seeder.TEST_ID }
                .localDate
        assertEquals(
            "a one off: every 3650 days from the day it is due, so the daily job never adds a second day",
            Recurrence.EveryNDays(3650, dueDate),
            test.recurrence,
        )
        val testOccurrences = open.filter { it.templateId == Seeder.TEST_ID }
        assertEquals(1, testOccurrences.size)

        for (index in 1..3) {
            val daily = checkNotNull(templates.findById("${Seeder.DAILY_PREFIX}$index"))
            assertEquals(Criticality.STANDARD, daily.criticality)
            assertTrue(daily.active)
            assertEquals(2, open.count { it.templateId == daily.id })
        }
        assertEquals(
            Seeder.DAILY,
            (1..3).map { checkNotNull(templates.findById("${Seeder.DAILY_PREFIX}$it")).timeOfDay },
        )
    }

    @Test
    fun `the test reminder is due three to four minutes from now`() {
        seeder().seed()

        val due =
            fixture.occurrences
                .findOpen()
                .single { it.templateId == Seeder.TEST_ID }
                .scheduledInstant

        assertTrue("at least three minutes out: $due", due >= now + 3.minutes)
        assertTrue("less than four and a half: $due", due < now + 4.minutes + 30.seconds)
        assertEquals("at a whole minute", 0L, due.toEpochMilliseconds() % 60_000L)
    }

    @Test
    fun `exactly one alarm is armed, for the test reminder`() {
        seeder().seed()

        val alarms = fixture.alarms()
        assertEquals(1, alarms.size)
        val test = fixture.occurrences.findOpen().single { it.templateId == Seeder.TEST_ID }
        assertEquals(test.alarmSlot, fixture.requestCode(alarms.single()))
        assertEquals(0, fixture.checkAlarms().size)
    }

    @Test
    fun `seeding twice changes nothing the second time`() {
        seeder().seed()
        val before =
            fixture.occurrences
                .findOpen()
                .map { it.id }
                .toSet()

        val second = seeder().seed()

        assertEquals(SeedResult.AlreadySeeded, second)
        assertEquals(
            before,
            fixture.occurrences
                .findOpen()
                .map { it.id }
                .toSet(),
        )
        assertEquals(1, fixture.alarms().size)
    }

    // ADR 0079 item 11: the test reminder is an ordinary active template, one off in effect because it recurs every
    // 3650 days. The daily materialisation, run for each of the next three days (the step that would give a Daily
    // template a second occurrence), adds none for it; it is the one alarm armed; and no inactive template holds an
    // open occurrence.
    @Test
    fun `the test reminder is active, one off in effect, and rings`() {
        seeder().seed()
        val test = checkNotNull(templates.findById(Seeder.TEST_ID))
        assertTrue("active", test.active)
        val materialise = fixture.graph.get<MaterialiseCommand>()

        for (day in 1..3) {
            fixture.clock.now = now + day.days
            materialise.dispatch(fixture.clock.now)
        }

        val ofTest = fixture.occurrences.findForTemplate(Seeder.TEST_ID)
        assertEquals("one occurrence for the test template after three days of the daily job", 1, ofTest.size)
        val armedFor = ofTest.single()
        val alarm = fixture.alarms().single()
        assertEquals("it is armed", armedFor.alarmSlot, fixture.requestCode(alarm))
        val inactive =
            templates
                .findAll()
                .filter { !it.active }
                .map { it.id }
                .toSet()
        assertEquals(
            "no inactive template has an open occurrence",
            emptyList<String>(),
            fixture.occurrences
                .findOpen()
                .filter { it.templateId in inactive }
                .map { it.id },
        )
    }

    @Test
    fun `the seed screen is in the debug manifest as a launcher entry`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, SeedActivity::class.java), 0)

        assertTrue("a launcher entry has to be exported", info.exported)
    }
}
