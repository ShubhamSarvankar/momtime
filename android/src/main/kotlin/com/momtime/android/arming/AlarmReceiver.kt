package com.momtime.android.arming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Instant

/**
 * Where the receiver gets the fire path from. The application sets [provider] when it starts; a test sets
 * its own. A receiver is created by the platform with no arguments, so it cannot be given one.
 */
object ArmingEntryPoint {
    @Volatile
    var provider: ((Context) -> AlarmFireHandler)? = null

    internal fun handler(context: Context): AlarmFireHandler =
        checkNotNull(provider) { "no fire path is installed" }(context)
}

/**
 * Receives the alarm (ADR 0053). It is declared `exported="false"` and every `PendingIntent` that reaches it
 * names it as an explicit component, so nothing outside the app can send it an alarm.
 *
 * The work is the fire path, which reads and writes the database, so it is not done on the main thread: the
 * receiver takes `goAsync()` and runs on a small bounded executor, and `finish()` is called in a `finally`,
 * so an exception anywhere cannot leave the broadcast open until the platform kills the process for it.
 * The log line carries the exception's class and nothing else (CLAUDE.md invariant 11).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        // The request code is not visible to a receiver, so the slot also travels as an extra. A missing extra
        // reads as a slot no occurrence owns, which the fire path handles like any other stale alarm.
        val slot = intent.getIntExtra(AlarmIntents.EXTRA_SLOT, NO_SLOT)
        val rungMillis = intent.getLongExtra(AlarmIntents.EXTRA_RUNG_MILLIS, NO_RUNG)
        val appContext = context.applicationContext
        executor.execute {
            try {
                ArmingEntryPoint.handler(appContext).onFire(slot, Instant.fromEpochMilliseconds(rungMillis))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                Log.e(TAG, "alarm fire failed: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    internal companion object {
        private const val TAG = "MomTimeAlarm"
        private const val NO_RUNG = 0L
        private const val NO_SLOT = -1

        /** Two threads: a fire is short, and there is one alarm at a time. Bounded so a burst cannot grow it. */
        internal val executor: ExecutorService = Executors.newFixedThreadPool(2)
    }
}
