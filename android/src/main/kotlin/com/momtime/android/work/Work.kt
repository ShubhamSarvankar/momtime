package com.momtime.android.work

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.momtime.android.arming.AppStart
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.arming.Watchdog
import com.momtime.android.system.SystemEvent
import com.momtime.shared.data.MaterialiseCommand
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

/**
 * One daily materialisation pass: regenerate the rolling window, then make sure the next alarm exists, because
 * a new occurrence can be earlier than the rung that is armed. Unique work is an efficiency measure only. The
 * transaction in `materialiseWindow` is what keeps two overlapping runs from leaving a duplicate (ADR 0036).
 */
class MaterialisationPass internal constructor(
    private val materialise: MaterialiseCommand,
    private val coordinator: ArmingCoordinator,
    private val now: () -> Instant,
) {
    /** Materialises, then ensures armed. Returns how many occurrences were created. */
    fun run(): Int = materialise.dispatch(now()).also { coordinator.ensureArmed() }
}

/** What the workers run. A worker is created by `WorkManager` with no arguments, so it cannot be given these. */
class WorkPasses internal constructor(
    val watchdog: Watchdog,
    val materialisation: MaterialisationPass,
    val system: AppStart,
)

/**
 * Where the workers find the passes. The application sets [provider] when it starts, and a test sets its own,
 * as it does for the alarm receiver (`ArmingEntryPoint`). `WorkManager` starts with its default initializer
 * before the application's `onCreate`, but a worker runs only after the process has started, so by then the
 * provider is set (ADR 0057).
 */
object WorkEntryPoint {
    @Volatile
    var provider: ((Context) -> WorkPasses)? = null

    internal fun passes(context: Context): WorkPasses =
        checkNotNull(provider) { "no work passes are installed" }(context)
}

/**
 * The watchdog job (ADR 0058). It does the pass and nothing else: it never starts the ringer. A transient
 * failure asks for a retry, so `WorkManager` tries the pass again after its backoff and not a whole period later;
 * the periodic job goes on either way (a failed result does not end a periodic job in 2.12, which `WorkTest`
 * shows). A store failure is not an exception here at all (ADR 0054). The log line carries the exception's
 * class only (invariant 11).
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result =
        try {
            WorkEntryPoint.passes(applicationContext).watchdog.run()
            Result.success()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            Log.e(TAG, "watchdog pass failed: ${e.javaClass.simpleName}")
            Result.retry()
        }

    private companion object {
        const val TAG = "MomTimeWork"
    }
}

/** The materialisation job, daily and on demand. Like the watchdog it asks for a retry when a pass fails. */
class MaterialisationWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result =
        try {
            WorkEntryPoint.passes(applicationContext).materialisation.run()
            Result.success()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            Log.e(TAG, "materialisation failed: ${e.javaClass.simpleName}")
            Result.retry()
        }

    private companion object {
        const val TAG = "MomTimeWork"
    }
}

/**
 * The system pass (ADR 0067): what a system broadcast is answered with. It dispatches `Reconcile` and calls
 * `ensureArmed`, which is [AppStart.run], whatever the broadcast was: the same two steps, and the same one entry
 * point, as a process start. It never starts the ringer and never starts a foreground service: an overdue rung is
 * armed for now and the alarm path rings it when it fires (ADR 0056). A transient failure asks for a retry.
 */
class SystemEventWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result =
        try {
            if (SystemEvent.entries.none { it.name == inputData.getString(Work.KEY_EVENT) }) {
                Result.failure()
            } else {
                WorkEntryPoint.passes(applicationContext).system.run()
                Result.success()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            Log.e(TAG, "system pass failed: ${e.javaClass.simpleName}")
            Result.retry()
        }

    private companion object {
        const val TAG = "MomTimeWork"
    }
}

/**
 * Enqueues the work (ADR 0057). Every call is safe to repeat: the periodic jobs are unique and keep the one
 * that exists, so enqueueing again at every start never resets a timer.
 */
object Work {
    const val WATCHDOG = "momtime.watchdog"
    const val MATERIALISE_DAILY = "momtime.materialise.daily"
    const val MATERIALISE_NOW = "momtime.materialise.now"

    /** `WorkManager`'s floor for a periodic job. */
    const val WATCHDOG_PERIOD_MINUTES = 15L
    const val MATERIALISE_PERIOD_HOURS = 24L

    /** The watchdog at the 15 minute floor. KEEP: an existing job is left alone, so its timer is not reset. */
    fun scheduleWatchdog(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(
            WATCHDOG,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(WATCHDOG_PERIOD_MINUTES, TimeUnit.MINUTES).build(),
        )
    }

    /** The daily materialisation. KEEP, for the same reason. */
    fun scheduleDailyMaterialisation(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(
            MATERIALISE_DAILY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaterialisationWorker>(MATERIALISE_PERIOD_HOURS, TimeUnit.HOURS).build(),
        )
    }

    /**
     * One materialisation run now. The template edit path (Phase 3) calls this after an edit. A run that is
     * already going when the edit lands may have read the old template, so a new request is appended after
     * it, and replaces one that failed or was cancelled. Not REPLACE: that would cancel a run in progress.
     */
    fun enqueueMaterialisation(workManager: WorkManager) {
        workManager.enqueueUniqueWork(
            MATERIALISE_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MaterialisationWorker>().build(),
        )
    }

    const val KEY_EVENT = "event"

    /** The unique name of the system pass for [event]. */
    fun systemName(event: SystemEvent) = "momtime.system.${event.name.lowercase()}"

    /**
     * One system pass now, for [event] (ADR 0067): unique one time work, appended after one that is running so that a
     * broadcast that lands during a pass is still answered by a pass that began after it, and replacing one that
     * failed or was cancelled. Not REPLACE: that would cancel a pass in progress. Returns the operation, whose
     * result completes when the work is written, which is what the receiver waits for before it finishes.
     */
    fun enqueueSystemEvent(
        context: Context,
        event: SystemEvent,
    ): Operation =
        WorkManager.getInstance(context).enqueueUniqueWork(
            systemName(event),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SystemEventWorker>()
                .setInputData(Data.Builder().putString(KEY_EVENT, event.name).build())
                .build(),
        )

    /** The two periodic jobs, for the application's start. Each runs once at once when it is first enqueued. */
    fun scheduleAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        scheduleWatchdog(workManager)
        scheduleDailyMaterialisation(workManager)
    }
}
