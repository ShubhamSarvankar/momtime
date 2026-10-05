package com.momtime.android.reliability

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The `PendingIntent` of the check she starts (ADR 0069). A reminder's `PendingIntent` is told apart by its request
 * code, which is its occurrence's `alarmSlot` (invariant 10). The check has no occurrence and so no slot: its request
 * code is the constant [REQUEST_CODE] and nothing else. That is a named exception to invariant 10, and it is safe
 * because the slot counter starts at 1, so no reminder ever has code 0, and because the check also has an action and
 * a receiver component of its own, so nothing `ensureArmed` arms, replaces or cancels can name it.
 *
 * It is explicit and `FLAG_IMMUTABLE`, and the receiver is not exported, so only this app's own alarm can reach it.
 */
internal object CheckIntents {
    const val ACTION_CHECK = "com.momtime.android.action.RELIABILITY_CHECK"
    const val EXTRA_CHECK_ID = "com.momtime.android.extra.CHECK_ID"

    /** Never an `alarmSlot`: the counter starts at 1 (ADR 0069). */
    const val REQUEST_CODE = 0

    private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE

    private fun intent(context: Context): Intent =
        Intent(ACTION_CHECK).setComponent(ComponentName(context, CheckReceiver::class.java))

    /** The alarm of check [checkId]. */
    fun fire(
        context: Context,
        checkId: Long,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent(context).putExtra(EXTRA_CHECK_ID, checkId),
            IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The existing alarm of the check, or null if none is armed. It creates nothing. */
    fun existing(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent(context), IMMUTABLE or PendingIntent.FLAG_NO_CREATE)

    /** What the system shows for the alarm clock and opens when she taps it: the screen that started the check. */
    fun show(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            ReliabilityCheckActivity.intent(context),
            IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
