package com.momtime.android.arming

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlin.time.Instant

/**
 * The `PendingIntent` an alarm fires. Its request code is the occurrence's `alarmSlot` and nothing else
 * (CLAUDE.md invariant 10, ARCHITECTURE.md section 5.4): never a hash of an id, because a collision cancels
 * an alarm and does not reproduce on a bench. It is `FLAG_IMMUTABLE`, so nothing that holds it can change
 * what it fires, and `FLAG_UPDATE_CURRENT`, so arming the same slot again replaces the extras in place.
 *
 * The intent is explicit: it names [AlarmReceiver] as its component, so only that receiver can get it, and
 * the receiver is not exported. `Intent.filterEquals` ignores extras, so two intents for the same slot are
 * the same `PendingIntent` whatever rung they carry, which is what makes re arming a replace in place.
 */
internal object AlarmIntents {
    const val ACTION_FIRE = "com.momtime.android.action.ALARM_FIRE"
    const val EXTRA_RUNG_MILLIS = "com.momtime.android.extra.RUNG_MILLIS"
    const val EXTRA_SLOT = "com.momtime.android.extra.SLOT"

    private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE

    private fun intent(context: Context): Intent =
        Intent(ACTION_FIRE).setComponent(ComponentName(context, AlarmReceiver::class.java))

    /** The `PendingIntent` for [slot], carrying the instant of the rung it is armed for. */
    fun fire(
        context: Context,
        slot: Int,
        rung: Instant,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            slot,
            intent(context)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_RUNG_MILLIS, rung.toEpochMilliseconds()),
            IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The existing `PendingIntent` for [slot], or null if none is armed. It creates nothing. */
    fun existing(
        context: Context,
        slot: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(context, slot, intent(context), IMMUTABLE or PendingIntent.FLAG_NO_CREATE)

    /**
     * What the system shows for an alarm clock: the app's launch screen, if it has one. There is no activity
     * yet, so this is null for now (`MANUAL_CHECKS.md` P2-11). Its request code is the slot as well; an
     * activity `PendingIntent` is a different kind from a broadcast one and cannot collide with it.
     */
    fun show(
        context: Context,
        slot: Int,
    ): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, slot, it, IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
}
