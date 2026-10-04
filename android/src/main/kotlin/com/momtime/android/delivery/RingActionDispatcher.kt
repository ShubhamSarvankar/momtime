package com.momtime.android.delivery

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.momtime.android.ring.RingItem
import com.momtime.android.ring.RingSessions
import com.momtime.android.ring.RingStyle
import com.momtime.shared.data.ActionResult
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.engine.OccurrenceAction

/**
 * Takes one of her actions to the domain and sees to what follows (ADR 0066). It is what the controller's two `act`
 * entry points, the ring screen's and a notification button's, both call.
 *
 * The domain's command writes the action's own event and changes the occurrence's state in one transaction and
 * nothing else (invariant 3). `ensureArmed` runs after it, inside the same exclusion the fire path and the
 * watchdog use, so neither ever reads the half done state, and arms what is next: for a snooze, the snooze's end.
 * Then the occurrence leaves the ring session and its notification is cancelled; when it was the last one in the
 * session the session ends and the ringer stops, which stops the sound and the vibration. An action the domain
 * refuses is refused visibly: the offered actions are refreshed on the screen and on the notification, so a
 * button that no longer works is gone and not left to do nothing.
 */
internal class RingActionDispatcher(
    private val context: Context,
    private val sessions: RingSessions,
    private val ringer: RingerLauncher,
    private val domain: RingDomain,
) {
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    fun dispatch(
        occurrenceId: String,
        action: OccurrenceAction,
    ) {
        val occurrence = domain.occurrences.findById(occurrenceId) ?: return
        // Exclusive of the fire path and the watchdog: between the domain's write and the re arm the state is half
        // done, and a watchdog pass must not read it as a lost alarm. The next alarm is armed whatever happens.
        val result =
            domain.coordinator.exclusive {
                try {
                    domain.actions.dispatch(occurrenceId, action, domain.now())
                } finally {
                    domain.coordinator.ensureArmed()
                }
            }
        when (result) {
            ActionResult.Done -> taken(occurrence)
            ActionResult.NotAvailable -> refused(occurrence)
            ActionResult.UnknownOccurrence -> Unit
        }
    }

    /** She acted on [occurrence]: it is no longer due, so it leaves the session and its notification goes. */
    private fun taken(occurrence: Occurrence) {
        notifications.cancel(occurrence.alarmSlot)
        val resolved = sessions.resolve(occurrence.id)
        if (resolved.ended) {
            ringer.stop()
            notifications.cancel(RingNotifications.RING_ID)
        } else if (resolved.remaining > 0) {
            refreshRingNotification()
        }
    }

    /**
     * The domain would not take the action: what is offered has changed since it was shown. The screen and the
     * notification are brought up to date, so the button is gone and not a button that does nothing.
     */
    private fun refused(occurrence: Occurrence) {
        val offered = domain.actions.available(occurrence.id, domain.now())
        sessions.refresh(occurrence.id, offered)
        if (offered.isEmpty()) {
            // It is terminal now (acted on elsewhere, or its grace ended): nothing is due for it.
            taken(occurrence)
        } else {
            refreshRingNotification()
            repostReminder(occurrence, offered)
        }
    }

    /** Posts the reminder notification of [occurrence] again with [offered], if one is posted for it. */
    private fun repostReminder(
        occurrence: Occurrence,
        offered: Set<OccurrenceAction>,
    ) {
        val template = domain.templates.findById(occurrence.templateId) ?: return
        val posted = notifications.activeNotifications.firstOrNull { it.id == occurrence.alarmSlot } ?: return
        val item =
            RingItem(
                occurrence.id,
                template.title,
                template.dosage,
                template.doctorInstructions,
                occurrence.alarmSlot,
                offered,
            )
        val late = posted.notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT) != null
        notifications.notify(
            occurrence.alarmSlot,
            RingNotifications.reminder(context, item, posted.notification.channelId, late),
        )
    }

    private fun refreshRingNotification() {
        val style: RingStyle = sessions.style ?: return
        notifications.notify(
            RingNotifications.RING_ID,
            RingNotifications.ring(context, sessions.items(), style.channelId, style.fullScreenIntent),
        )
    }
}
