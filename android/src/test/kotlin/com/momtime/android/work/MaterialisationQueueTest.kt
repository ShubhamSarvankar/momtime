package com.momtime.android.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.t0
import com.momtime.android.data.template
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.ScheduleTemplateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `Work.enqueueMaterialisation` is unique one time work with `ExistingWorkPolicy.APPEND_OR_REPLACE` (ADR 0057): a
 * run already going when a template is edited may have read the old template, so the new request runs after it,
 * not instead of it, and does not cancel it. `WorkManager`'s test executor is synchronous by default, which can
 * never have a run in progress while a second is enqueued, so this test gives `WorkManager` a real thread pool and
 * holds the first run on a latch, through the pass's `now` seam. KEEP would drop the second request and REPLACE
 * would cancel the first; each fails this test.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [36])
class MaterialisationQueueTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val executor = Executors.newFixedThreadPool(4)
    private lateinit var workManager: WorkManager

    private val firstRunIsHeld = CountDownLatch(1)
    private val releaseFirstRun = CountDownLatch(1)
    private val runs = AtomicInteger()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(executor).build(),
        )
        workManager = WorkManager.getInstance(context)
        fixture.graph.get<ScheduleTemplateRepository>().insert(template("daily"))
        fixture.clock.now = t0
        val pass =
            MaterialisationPass(fixture.graph.get<MaterialiseCommand>(), fixture.coordinator) {
                // The seam: the first run stops here until the test lets it go.
                if (runs.incrementAndGet() == 1) {
                    firstRunIsHeld.countDown()
                    releaseFirstRun.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
                fixture.clock.now
            }
        WorkEntryPoint.provider = { WorkPasses(fixture.passes.watchdog, pass, fixture.passes.system) }
    }

    @After
    fun tearDown() {
        releaseFirstRun.countDown()
        executor.shutdownNow()
        WorkEntryPoint.provider = null
        fixture.close()
    }

    private fun states(): List<WorkInfo.State> =
        workManager.getWorkInfosForUniqueWork(Work.MATERIALISE_NOW).get().map { it.state }

    private fun awaitStates(expected: List<WorkInfo.State>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (states() != expected && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        assertEquals(expected, states())
    }

    @Test
    fun `a run enqueued while another is going runs after it and does not replace it`() {
        Work.enqueueMaterialisation(workManager)
        assertTrue("the first run started and is held", firstRunIsHeld.await(WAIT_SECONDS, TimeUnit.SECONDS))
        awaitStates(listOf(WorkInfo.State.RUNNING))

        // An edit lands while the first run is in progress.
        Work.enqueueMaterialisation(workManager)

        assertEquals(
            "the second request is kept, waiting for the first, and the first is not cancelled",
            setOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED),
            states().toSet(),
        )
        Thread.sleep(OBSERVE_MILLIS)
        assertEquals("the second run has not started while the first is held", 1, runs.get())

        releaseFirstRun.countDown()
        awaitStates(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED))
        assertEquals("both runs ran, the second after the first", 2, runs.get())
        assertEquals(2, fixture.occurrences.findForTemplate("daily").size)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val OBSERVE_MILLIS = 300L
        const val POLL_MILLIS = 20L
    }
}
