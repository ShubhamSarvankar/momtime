package com.momtime.android.data

import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.data.TimeZoneChangeCommand
import com.momtime.shared.data.Transactor
import com.momtime.shared.domain.Occurrence
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
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
 * The zone change command is one transaction (ADR 0068), asked of the driver the app ships, as `MaterialiseRaceTest`
 * asks it of materialisation. The command moves open occurrences and then updates the templates' zones. A
 * materialisation run that landed in between would read the old zone and insert new dates at the wrong instants,
 * and the idempotence guard would then keep anything from repairing them.
 *
 * The command is held on a latch inside its transaction (the occurrence repository it moves through blocks on its first
 * move). A materialisation run is started while it is held. With the transaction, the run waits for the connection and
 * completes only after the command has committed, so it reads the new zone and every date it inserts is in it. Without
 * the transaction the run completes at once with the old zone, and the dates it inserted stay in it.
 *
 * What this shows is the Android framework's Java code on Robolectric's native SQLite, at SDK 29 and 36. It is not a
 * device run.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class TimeZoneRaceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    /** An occurrence repository whose first move waits, so the command is held inside its transaction. */
    private class HoldingOccurrences(
        private val inner: OccurrenceRepository,
    ) : OccurrenceRepository by inner {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        override fun reschedule(
            occurrenceId: String,
            scheduledInstant: Instant,
            zone: TimeZone,
        ) {
            if (first.compareAndSet(true, false)) {
                held.countDown()
                check(release.await(60, TimeUnit.SECONDS)) { "the command was never released" }
            }
            inner.reschedule(occurrenceId, scheduledInstant, zone)
        }
    }

    @Test
    fun `a materialisation run during the command waits and inserts the new dates in the new zone`() {
        val graph = TestGraph(context).also { graphs.add(it) }
        val template = graph.seedTemplate()
        val templates = graph.get<ScheduleTemplateRepository>()
        val occurrences = graph.get<OccurrenceRepository>()
        val ids = AtomicInteger()
        // One open occurrence exists, in the template's zone, for the command to move.
        occurrences.materialiseWindow(template, start, start + 1.hours * 24) { "seed-${ids.incrementAndGet()}" }
        val before = occurrences.findForTemplate(template.id).map { it.id }.toSet()
        assertTrue("an occurrence to move", before.isNotEmpty())
        val holding = HoldingOccurrences(occurrences)
        val command = TimeZoneChangeCommand(templates, holding, graph.get<Transactor>())
        val materialise = MaterialiseCommand(templates, occurrences) { "run-${ids.incrementAndGet()}" }
        val commandDone = CountDownLatch(1)
        val runDone = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()

        val a =
            Thread {
                runCatching {
                    command.dispatch(
                        tokyo,
                        start,
                    )
                }.onFailure(failures::add).also { commandDone.countDown() }
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

        assertEquals("the template follows the device", tokyo, checkNotNull(templates.findById(template.id)).timeZoneId)
        val all = occurrences.findForTemplate(template.id)
        val created = all.filter { it.id !in before }
        assertTrue("the run inserted new dates", created.isNotEmpty())
        for (o in all) assertEquals("${o.localDate} is in the new zone", tokyo, o.timeZoneId)
        for (o in created) assertEquals("${o.localDate} is at 08:00 there", wallClock(o, tokyo), o.scheduledInstant)
    }

    private fun wallClock(
        o: Occurrence,
        zone: TimeZone,
    ): Instant = LocalDateTime(o.localDate, LocalTime(8, 0)).toInstant(zone)
}
