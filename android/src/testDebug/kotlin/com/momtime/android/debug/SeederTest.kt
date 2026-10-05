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
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
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
        assertFalse("a one off: the daily job never adds a second day", test.active)
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

    @Test
    fun `the seed screen is in the debug manifest as a launcher entry`() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, SeedActivity::class.java), 0)

        assertTrue("a launcher entry has to be exported", info.exported)
    }
}
