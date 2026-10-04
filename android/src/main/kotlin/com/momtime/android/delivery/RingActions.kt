package com.momtime.android.delivery

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.momtime.shared.engine.OccurrenceAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The `PendingIntent`s behind a notification's action buttons (ADR 0066). Each one is immutable, explicit and
 * reaches a receiver that is not exported: nothing outside the app can send one, and nothing that holds one can
 * change which occurrence or action it names.
 *
 * It names the occurrence by its `alarmSlot` and never by an id, and its request code derives from that slot
 * (CLAUDE.md invariant 10): `slot * 3 + the action's position`, so the three actions of one occurrence never share
 * a code and two occurrences never collide. The intent's own action string differs per action as well, and it
 * names [RingActionReceiver] as its component, so none of these can be the same `PendingIntent` as an alarm's
 * (whose component is the alarm receiver).
 */
internal object RingActionIntents {
    private const val EXTRA_SLOT = "com.momtime.android.extra.ACTION_SLOT"
    private const val ACTION_PREFIX = "com.momtime.android.action.RING_"

    private fun intent(
        context: Context,
        action: OccurrenceAction,
    ): Intent = Intent(ACTION_PREFIX + action.name).setComponent(ComponentName(context, RingActionReceiver::class.java))

    /** The request code for [action] on the occurrence in [slot]. */
    fun requestCode(
        slot: Int,
        action: OccurrenceAction,
    ): Int = slot * OccurrenceAction.entries.size + action.ordinal

    fun pending(
        context: Context,
        slot: Int,
        action: OccurrenceAction,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(slot, action),
            intent(context, action).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The action a received intent names, or null if it names none of ours. */
    fun actionOf(intent: Intent): OccurrenceAction? =
        OccurrenceAction.entries.firstOrNull { ACTION_PREFIX + it.name == intent.action }

    fun slotOf(intent: Intent): Int = intent.getIntExtra(EXTRA_SLOT, NO_SLOT)

    const val NO_SLOT = -1
}

/** What the action receiver reaches the rest of the app through. Set when the application starts. */
internal fun interface NotificationActionHandler {
    /** Dispatches [action] on the occurrence that owns [slot]. An unknown slot does nothing. */
    fun act(
        slot: Int,
        action: OccurrenceAction,
    )
}

internal object NotificationActionEntryPoint {
    @Volatile
    var provider: (() -> NotificationActionHandler)? = null
}

/**
 * Receives a notification's acknowledge, snooze or skip button (ADR 0066). Declared `exported="false"`, and every
 * `PendingIntent` that reaches it is explicit and immutable ([RingActionIntents]). The work reads and writes the
 * database, so it is not done on the main thread: it takes `goAsync()`, runs on a small bounded executor and always
 * finishes. The log line carries the exception's class and nothing else (CLAUDE.md invariant 11).
 *
 * Swiping a notification away never reaches it: no notification sets a delete intent, so dismissal writes nothing
 * and the next rung still fires (ARCHITECTURE.md section 4.6).
 */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val action = RingActionIntents.actionOf(intent)
        val slot = RingActionIntents.slotOf(intent)
        executor.execute {
            try {
                if (action != null) NotificationActionEntryPoint.provider?.invoke()?.act(slot, action)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                Log.e(TAG, "notification action failed: ${e.javaClass.simpleName}")
            } finally {
                pending?.finish()
            }
        }
    }

    internal companion object {
        private const val TAG = "MomTimeAction"

        /** Two threads: an action is short, and the actions are rare. Bounded so a burst cannot grow it. */
        internal val executor: ExecutorService = Executors.newFixedThreadPool(2)
    }
}
