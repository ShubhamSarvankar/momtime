package com.momtime.android.delivery

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.momtime.android.ring.RingItem
import com.momtime.android.ring.RingSessions
import com.momtime.shared.domain.Occurrence

/**
 * The notification under an occurrence's slot, posted again with the text and the actions in [item] and the shape
 * it already has: a per occurrence notification of a session that has spread (ADR 0080), or a reminder that arrived
 * as a notification (Tier 1, Gentle, a silent one), on the channel it is on and saying it is late if it did. Only a
 * notification that is still showing, read from the platform's active notifications, is posted again: one she
 * swiped away is not brought back, by a refused action or by an edit (decided by Claude (technical review), PR 6 of
 * Phase 3). The repost is only alert once and writes nothing.
 */
internal object SlotNotifications {
    /** Reposts the notification under [occurrence]'s slot if one is showing. Returns whether one was. */
    fun refresh(
        context: Context,
        sessions: RingSessions,
        occurrence: Occurrence,
        item: RingItem,
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted =
            manager.activeNotifications.firstOrNull { it.id == occurrence.alarmSlot }?.notification ?: return false
        val own = sessions.isSpread && sessions.items().any { it.occurrenceId == occurrence.id }
        val notification =
            if (own) {
                RingNotifications.occurrence(context, item, posted.channelId)
            } else {
                val late = posted.extras.getCharSequence(Notification.EXTRA_SUB_TEXT) != null
                RingNotifications.reminder(context, item, posted.channelId, late, alertOnce = true)
            }
        manager.notify(occurrence.alarmSlot, notification)
        return true
    }
}
