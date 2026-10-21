package com.momtime.android.data

import com.momtime.shared.data.EditResult
import com.momtime.shared.data.EditTemplateCommand
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.data.Transactor
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Occurrence
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The template edit command is one transaction (ADR 0079 item 1), asked of the driver the app ships, as
 * `TimeZoneRaceTest` asks it of the zone change command. The command writes the template and then moves the open
 * occurrences that have not come due. A materialisation run that landed between the two would read a half applied
 * edit, and the idempotence guard would then keep anything from repairing what it inserted.
 *
 * The command is held on a latch inside its transaction (the occurrence repository it moves through blocks on its
 * first move). A materialisation run is started while it is held. With the transaction, the run waits for the
 * connection and completes only after the command has committed, so it reads the edited template and every date it
 * inserts is at the edited time. Without the transaction the run completes at once.
 *
 * What this shows is the Android framework's Java code on Robolectric's native SQLite, at SDK 29 and 36. It is not a
 * device run.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class TemplateEditRaceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    /** An occurrence repository whose first edit write waits, so the command is held inside its transaction. */
    private class HoldingOccurrences(
        private val inner: OccurrenceRepository,
    ) : OccurrenceRepository by inner {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun applyEdit(
            occurrenceId: String,
            scheduledInstant: Instant,
            criticality: Criticality,
        ) {
            if (first.compareAndSet(true, false)) {
                held.countDown()
                check(release.await(60, TimeUnit.SECONDS)) { "the command was never released" }
            }
            inner.applyEdit(occurrenceId, scheduledInstant, criticality)
        }
    }

    @Test
    fun `a materialisation run during the edit waits and inserts the new dates at the edited time`() {
        val graph = TestGraph(context).also { graphs.add(it) }
        val template = graph.seedTemplate()
        val templates = graph.get<ScheduleTemplateRepository>()
        val occurrences = graph.get<OccurrenceRepository>()
        val ids = AtomicInteger()
        // One open occurrence exists, at 08:00 in the template's zone and not yet due, for the command to move.
        occurrences.materialiseWindow(template, start, start + 1.hours * 24) { "seed-${ids.incrementAndGet()}" }
        val before = occurrences.findForTemplate(template.id).map { it.id }.toSet()
        assertTrue("an occurrence to move", before.isNotEmpty())
        val holding = HoldingOccurrences(occurrences)
        val command =
            EditTemplateCommand(templates, holding, graph.get<EventRepository>(), graph.get<Transactor>()) {
                "edit-${ids.incrementAndGet()}"
            }
        val materialise = MaterialiseCommand(templates, occurrences) { "run-${ids.incrementAndGet()}" }
        val commandDone = CountDownLatch(1)
        val runDone = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()
        val results = mutableListOf<EditResult>()
        val edited = template.copy(timeOfDay = LocalTime(9, 0))

        val a =
            Thread {
                runCatching { results += command.dispatch(edited, start) }
                    .onFailure(failures::add)
                    .also { commandDone.countDown() }
            }
        val b =
            Thread { runCatching { materialise.dispatch(start) }.onFailure(failures::add).also { runDone.countDown() } }
        a.start()
        assertTrue("the command never reached its first move", holding.held.await(30, TimeUnit.SECONDS))
        b.start()

        // With the transaction the run cannot complete while the command holds it.
        assertFalse(
            "the materialisation run completed while the command held its transaction",
            runDone.await(2, TimeUnit.SECONDS),
        )
        holding.release.countDown()
        assertTrue("the command did not finish", commandDone.await(60, TimeUnit.SECONDS))
        assertTrue("the run did not finish", runDone.await(60, TimeUnit.SECONDS))
        assertEquals("a thread failed: $failures", emptyList<Throwable>(), failures)

        assertEquals("the edit moved the open occurrence", 1, (results.single() as EditResult.Edited).moved)
        assertEquals(LocalTime(9, 0), checkNotNull(templates.findById(template.id)).timeOfDay)
        val all = occurrences.findForTemplate(template.id)
        val created = all.filter { it.id !in before }
        assertTrue("the run inserted new dates", created.isNotEmpty())
        for (o in all) assertEquals("${o.localDate} is at 09:00", wallClock(o), o.scheduledInstant)
    }

    private fun wallClock(o: Occurrence): Instant = LocalDateTime(o.localDate, LocalTime(9, 0)).toInstant(testZone)
}
