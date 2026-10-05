package com.momtime.android.reliability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Where the receiver gets the runner from. The application sets [provider] when it starts; a test sets its own. A
 * receiver is created by the platform with no arguments, so it cannot be given one (as `ArmingEntryPoint`).
 */
object CheckEntryPoint {
    @Volatile
    var provider: ((Context) -> CanaryRunner)? = null

    internal fun runner(context: Context): CanaryRunner =
        checkNotNull(provider) { "no check runner is installed" }(context)
}

/**
 * Receives the alarm of the check she started (ADR 0069). Not exported: only the explicit `PendingIntent` the runner
 * armed can reach it, and a test reads the merged manifest and fails if it is exported. It does the runner's one thing
 * off the main thread, with `goAsync()` finished in a `finally`, and logs the exception's class and nothing else
 * (CLAUDE.md invariant 11). It never touches a reminder.
 */
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val checkId = intent.getLongExtra(CheckIntents.EXTRA_CHECK_ID, NO_CHECK)
        val appContext = context.applicationContext
        executor.execute {
            try {
                CheckEntryPoint.runner(appContext).onFired(checkId)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                Log.e(TAG, "check fire failed: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    internal companion object {
        private const val TAG = "MomTimeCheck"
        private const val NO_CHECK = -1L

        /** One thread: there is one check at a time. */
        internal val executor: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
