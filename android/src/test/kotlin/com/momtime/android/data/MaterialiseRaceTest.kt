package com.momtime.android.data

import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.ScheduleTemplate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * ADR 0036's open question, asked of the driver the app ships: when two materialisation runs
 * overlap, does the second WAIT for the first, or is it REFUSED?
 *
 * Run A starts materialising and pauses inside the `generateId` callback, which the engine calls
 * after reading the existing dates and before inserting, so A holds its transaction open on a
 * latch. Run B then tries to materialise a slightly larger window through the same repository.
 * While A holds, B must not have completed and must not have started its work (its `generateId`
 * has not been called). After A is released, both complete, B sees A's rows and adds only the
 * missing dates, and the table holds no duplicate date, no duplicate alarmSlot and no partial batch.
 *
 * The control: two separate drivers on one file. There the second run is refused with a locked
 * database error, which shows the test can tell waiting from refusing.
 *
 * What this shows is the Android framework's Java code (its connection pool and transaction handling)
 * on Robolectric's native SQLite, at SDK 29 and 36. It is not a device run.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class MaterialiseRaceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    private fun graph(name: String? = null) =
        (if (name == null) TestGraph(context) else TestGraph(context, name)).also { graphs.add(it) }

    private class Run(
        val repo: OccurrenceRepository,
        val template: ScheduleTemplate,
        val days: Int,
        val tag: String,
        val pauseInside: Boolean,
    ) {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val ids = AtomicInteger()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<List<Occurrence>>>()
        private val first = AtomicBoolean(true)

        val thread =
            Thread {
                result.set(
                    runCatching {
                        repo.materialiseWindow(template, windowStartOf(), windowStartOf() + days.days) {
                            if (pauseInside && first.compareAndSet(true, false)) {
                                paused.countDown()
                                check(resume.await(60, TimeUnit.SECONDS)) { "$tag was never resumed" }
                            }
                            "$tag-${ids.incrementAndGet()}"
                        }
                    },
                )
                done.countDown()
            }

        private fun windowStartOf() = Instant.parse("2026-01-01T00:00:00Z")
    }

    private fun frames(thread: Thread) = thread.stackTrace.joinToString("\n") { "    at $it" }

    @Test
    fun `the contending run waits for the lock holder`() {
        val graph = graph()
        val template = graph.seedTemplate()
        val repo = graph.get<OccurrenceRepository>()
        val a = Run(repo, template, days = 10, tag = "a", pauseInside = true)
        val b = Run(repo, template, days = 12, tag = "b", pauseInside = false)

        a.thread.start()
        assertTrue("run A never reached its pause point", a.paused.await(30, TimeUnit.SECONDS))
        b.thread.start()

        // B must not complete, and must not have started its work, while A holds its transaction.
        assertFalse("run B completed while run A held its transaction", b.done.await(2, TimeUnit.SECONDS))
        assertEquals("run B started its work while run A held its transaction", 0, b.ids.get())
        val state = b.thread.state
        val where = frames(b.thread)
        println("RACE-MECHANISM B state=$state\n$where")
        assertTrue("B is not waiting: $state", state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING)
        assertTrue(
            "B is waiting, but not in the framework's connection pool:\n$where",
            where.contains("SQLiteConnectionPool.waitForConnection"),
        )

        a.resume.countDown()
        assertTrue("run A did not finish", a.done.await(60, TimeUnit.SECONDS))
        assertTrue("run B did not finish", b.done.await(60, TimeUnit.SECONDS))

        val resultA = checkNotNull(a.result.get())
        val resultB = checkNotNull(b.result.get())
        assertTrue("run A failed: ${resultA.exceptionOrNull()}", resultA.isSuccess)
        assertTrue("run B failed: ${resultB.exceptionOrNull()}", resultB.isSuccess)
        assertEquals(10, resultA.getOrThrow().size)
        assertEquals("B must add only the two dates A did not", 2, resultB.getOrThrow().size)
        val rows = repo.findForTemplate(template.id)
        assertEquals(12, rows.size)
        assertEquals("duplicate date", 12, rows.map { it.localDate }.toSet().size)
        assertEquals("duplicate alarmSlot", 12, rows.map { it.alarmSlot }.toSet().size)
    }

    // Two drivers on one file. B is refused: the file lock, not the framework's pool, is what it meets,
    // and SQLite's busy handler gives up after the framework's busy timeout.
    @Test
    fun `two drivers on one file refuse the contending run`() {
        val name = "race-two.db"
        val graphA = graph(name)
        val template = graphA.seedTemplate()
        val graphB = graph(name)
        // Open B's driver now, while nothing is locked, so that only the materialisation contends.
        graphB.get<OccurrenceRepository>().findPending()
        val a = Run(graphA.get<OccurrenceRepository>(), template, days = 10, tag = "a", pauseInside = true)
        val b = Run(graphB.get<OccurrenceRepository>(), template, days = 12, tag = "b", pauseInside = false)

        a.thread.start()
        assertTrue("run A never reached its pause point", a.paused.await(30, TimeUnit.SECONDS))
        b.thread.start()

        // B finishes while A is still holding: it was refused, not queued.
        assertTrue("run B was not refused while run A held", b.done.await(30, TimeUnit.SECONDS))
        assertEquals("A must still be paused", 1L, a.resume.count)
        val refusal = checkNotNull(b.result.get()).exceptionOrNull()
        assertNotNull("run B should have been refused, but succeeded", refusal)
        val chain = generateSequence<Throwable>(refusal) { it.cause }.toList()
        println("RACE-REFUSAL ${chain.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }}")
        assertTrue(
            "refused, but not with a locked database error: $chain",
            chain.any { it.message.orEmpty().contains("locked", ignoreCase = true) },
        )

        a.resume.countDown()
        assertTrue("run A did not finish", a.done.await(60, TimeUnit.SECONDS))
        assertTrue("run A failed", checkNotNull(a.result.get()).isSuccess)
        val rows = graphA.get<OccurrenceRepository>().findForTemplate(template.id)
        assertEquals("the refused run must leave nothing behind", 10, rows.size)
        assertEquals(10, rows.map { it.alarmSlot }.toSet().size)
    }
}
