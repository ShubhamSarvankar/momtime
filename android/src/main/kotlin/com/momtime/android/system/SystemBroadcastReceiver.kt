package com.momtime.android.system

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.momtime.android.work.Work

/**
 * The system broadcasts the alarm subsystem listens to (ADR 0067). Each one means the alarm that is armed may no
 * longer be the right one, so each is answered the same way: unique one time work that dispatches `Reconcile` and
 * then calls `ensureArmed`, the one entry point (ADR 0053). Nothing else happens here, and in particular nothing
 * rings: boot cannot start a `mediaPlayback` foreground service on Android 15 and later (CLAUDE.md), a worker never
 * starts the ringer, and an overdue rung is armed for now and left to the alarm path and the fire path, which
 * decide how it is presented (ADR 0056).
 *
 * - [SystemEvent.BOOT]: a restart clears every alarm. `BOOT_COMPLETED` only, so only after the first unlock;
 *   `LOCKED_BOOT_COMPLETED` is not handled (progress decision 10, and nothing here is direct boot aware).
 * - [SystemEvent.PACKAGE_REPLACED]: the app was updated. AOSP keeps an updated app's alarms, so this usually finds
 *   everything armed and changes nothing; `ensureArmed` is correct either way (ADR 0067).
 * - [SystemEvent.TIME_CHANGED]: the clock was set. The rung that is armed is re armed against the new time.
 * - [SystemEvent.EXACT_ALARM_PERMISSION_STATE_CHANGED]: sent on a grant, never on a revocation. It is used only to
 *   upgrade out of Tier 1; a revocation is learned by resolving capability again, never from a broadcast.
 *
 * It is declared `exported="false"`: the system sends these broadcasts as the system uid, which is allowed to reach
 * an unexported component (ADR 0067). It acts only on the actions in [SystemEvent] and ignores everything else, so
 * even a broadcast that did reach it with another action does nothing.
 *
 * It takes `goAsync()` and finishes when the work is enqueued, so the process is not left to die with the work
 * not yet written. The log line carries the exception's class and nothing else (CLAUDE.md invariant 11).
 */
class SystemBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val event = SystemEvent.fromAction(intent.action) ?: return
        val pending = goAsync()
        try {
            Work.enqueueSystemEvent(context.applicationContext, event).result.addListener(
                { pending?.finish() },
                Runnable::run,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            Log.e(TAG, "enqueueing the system pass failed: ${e.javaClass.simpleName}")
            pending?.finish()
        }
    }

    private companion object {
        const val TAG = "MomTimeSystem"
    }
}

/** The system broadcasts that are acted on, by the action each arrives with. Nothing else is. */
enum class SystemEvent(
    val action: String,
) {
    BOOT(Intent.ACTION_BOOT_COMPLETED),
    PACKAGE_REPLACED(Intent.ACTION_MY_PACKAGE_REPLACED),
    TIME_CHANGED(Intent.ACTION_TIME_CHANGED),
    EXACT_ALARM_PERMISSION_STATE_CHANGED(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED),
    ;

    companion object {
        /** The event [action] names, or null for any other action, which is ignored. */
        fun fromAction(action: String?): SystemEvent? = entries.firstOrNull { it.action == action }
    }
}
