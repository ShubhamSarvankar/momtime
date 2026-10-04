package com.momtime.android.di

import android.app.NotificationManager
import android.content.Context
import com.momtime.android.delivery.NotificationChannels
import com.momtime.android.delivery.RingNotifications

/**
 * Tells her that her reminders were reset (ADR 0051, ADR 0060). A corrupt shared database is set aside and a fresh
 * one opens with no templates, so nothing rings until she sets them up again, and she must be told. It is posted
 * on the Critical channel by the corruption handler, on both paths, when the corruption is found on open and in
 * the middle of a query. On the second the process ends right after, so it is posted first.
 *
 * It never throws: the handler must finish whatever happens, because reminders have to keep working.
 */
fun interface ResetNotifier {
    fun notifyReset()
}

class PlatformResetNotifier(
    private val context: Context,
) : ResetNotifier {
    @Suppress("TooGenericExceptionCaught")
    override fun notifyReset() {
        try {
            NotificationChannels.ensure(context)
            context.getSystemService(NotificationManager::class.java).notify(
                RingNotifications.RESET_ID,
                RingNotifications.reset(context),
            )
        } catch (_: RuntimeException) {
            // A notification that cannot be posted must not stop the handler from finishing.
        }
    }
}
