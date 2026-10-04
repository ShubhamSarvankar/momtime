package com.momtime.android.system

import android.content.Context
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.delivery.finished
import com.momtime.android.delivery.withPendingResult
import com.momtime.android.work.Pass
import com.momtime.android.work.Work
import com.momtime.android.work.WorkEntryPoint
import com.momtime.android.work.WorkPasses
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The system passes share one queue and every broadcast is appended to it (ADR 0068): unique one time work with
 * `APPEND_OR_REPLACE`. A broadcast that lands while a pass is running is answered by a fresh pass that begins after
 * the running one has finished, so it reads the clock and the zone as they are then. KEEP would drop it, and that pass
 * may have read the clock before the change. `WorkManager`'s test executor is synchronous by default, which can never
 * have a pass running while the next broadcast arrives, so this test gives `WorkManager` a real thread pool (as
 * `MaterialisationQueueTest` does) and holds the first pass on a latch through the pass seam.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [36])
class SystemQueueTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val executor = Executors.newFixedThreadPool(4)
    private lateinit var workManager: WorkManager

    private val firstPassIsHeld = CountDownLatch(1)
    private val releaseFirstPass = CountDownLatch(1)
    private val started = AtomicInteger()
    private val finishedPasses = AtomicInteger()

    /** What the clock said when each pass began, in the order the passes began. */
    private val clockAtStart = CopyOnWriteArrayList<Long>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(executor).build(),
        )
        workManager = WorkManager.getInstance(context)
        val counting =
            Pass {
                val index = started.incrementAndGet()
                clockAtStart += fixture.clock.now.toEpochMilliseconds()
                if (index == 1) {
                    // The seam: the first pass stops here until the test lets it go.
                    firstPassIsHeld.countDown()
                    releaseFirstPass.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
                finishedPasses.incrementAndGet()
            }
        WorkEntryPoint.provider =
            { WorkPasses(fixture.passes.watchdog, fixture.passes.materialisation, counting, counting) }
    }

    @After
    fun tearDown() {
        releaseFirstPass.countDown()
        executor.shutdownNow()
        WorkEntryPoint.provider = null
        fixture.close()
    }

    private fun states(): List<WorkInfo.State> =
        workManager.getWorkInfosForUniqueWork(Work.SYSTEM_QUEUE).get().map { it.state }

    private fun broadcast(action: String) {
        val receiver = SystemBroadcastReceiver()
        val pending = withPendingResult(receiver)
        shadowOf(receiver).onReceive(context, Intent(action), AtomicBoolean())
        assertTrue("the receiver did not finish", finished(pending))
    }

    private fun awaitStates(expected: List<WorkInfo.State>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (states() != expected && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        assertEquals(expected, states())
    }

    // Boot, and then a clock change while the boot pass is still running: the second must run after the first, and it
    // must read the clock as it is after the change, not as the first pass saw it.
    @Test
    fun `a broadcast that lands during a pass is answered by a fresh pass after it`() {
        val before = fixture.clock.now
        broadcast(Intent.ACTION_BOOT_COMPLETED)
        assertTrue("the first pass started and is held", firstPassIsHeld.await(WAIT_SECONDS, TimeUnit.SECONDS))
        awaitStates(listOf(WorkInfo.State.RUNNING))

        fixture.clock.now = before + kotlin.time.Duration.parse("3h")
        broadcast(Intent.ACTION_TIME_CHANGED)

        assertEquals(
            "the second request is kept, waiting for the first, which is not cancelled",
            setOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED),
            states().toSet(),
        )
        Thread.sleep(OBSERVE_MILLIS)
        assertEquals("the second pass has not started while the first is held", 1, started.get())

        releaseFirstPass.countDown()
        awaitStates(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.SUCCEEDED))
        assertEquals("both passes ran, the second after the first", 2, started.get())
        assertEquals(2, finishedPasses.get())
        assertEquals("the first pass began before the change", before.toEpochMilliseconds(), clockAtStart[0])
        assertEquals(
            "the fresh pass read the clock after the change",
            fixture.clock.now.toEpochMilliseconds(),
            clockAtStart[1],
        )
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val OBSERVE_MILLIS = 300L
        const val POLL_MILLIS = 20L
    }
}
