package com.momtime.android.delivery

import android.app.NotificationManager
import android.content.Context
import com.momtime.android.ring.RingSessions

/**
 * The session's notification as the session is now (ADR 0062, ADR 0090 item 3). While the session sounds it is the
 * ring notification, ongoing, the one the ringer service holds; once the sound has stopped (the cap, or "Stop the
 * sound") it is reposted as an ordinary one that keeps her actions in front of her: not ongoing, no full screen
 * intent, only alert once. When nothing is listed any more it is cancelled. Posted under one id either way, so a
 * change replaces what was there and never adds a second. With [alertOnce] the ring notification too is replaced
 * without alerting again: her text changed under a ring that is already sounding, and nothing new is due.
 */
internal object SessionNotifications {
    fun refresh(
        context: Context,
        sessions: RingSessions,
        alertOnce: Boolean = false,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val items = sessions.items()
        val style = sessions.style
        when {
            items.isEmpty() || style == null -> manager.cancel(RingNotifications.RING_ID)
            sessions.isActive ->
                manager.notify(
                    RingNotifications.RING_ID,
                    RingNotifications.ring(context, items, style.channelId, style.fullScreenIntent, alertOnce),
                )
            else ->
                manager.notify(
                    RingNotifications.RING_ID,
                    RingNotifications.dismissed(context, items, style.channelId),
                )
        }
    }
}
