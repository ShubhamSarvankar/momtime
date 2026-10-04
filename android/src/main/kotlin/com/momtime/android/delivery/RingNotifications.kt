package com.momtime.android.delivery

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import com.momtime.android.R
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingItem
import com.momtime.shared.engine.OccurrenceAction

/**
 * The notifications the delivery paths post (ADR 0060). Their text is hers where it is hers: a title, a dosage
 * and her doctor's instructions go in exactly as she typed them and are never parsed. Everything else is a
 * string resource.
 *
 * `setFullScreenIntent` is called only when the capability resolution says full screen intent is effective, and
 * never otherwise: on API 34 and above the platform refuses it from an app that has not been granted it, and
 * the answer to that is the Tier 2 path, not a try (CLAUDE.md, ADR 0050).
 */
internal object RingNotifications {
    /** The id of the ring session's notification. A slot is never this large, so it cannot collide with one. */
    const val RING_ID = Int.MAX_VALUE

    /** The reset notification's id: one notification, replaced if it is posted again. */
    const val RESET_ID = Int.MAX_VALUE - 1

    /**
     * The ring screen's `PendingIntent`. It names one activity and carries no slot, so its request code is 0:
     * slots start at 1, and an alarm `PendingIntent` names the receiver, not this activity, so the two can never
     * be the same `PendingIntent` (invariant 10 is about the alarm's code, which is the slot).
     */
    fun ringScreen(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            RingActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The notification for a ring session: ongoing, an alarm, on [channelId]. With [fullScreenIntent] the ring
     * screen is launched over the lock screen; without it this is a heads up, or nothing visible at all if
     * notifications are denied.
     */
    fun ring(
        context: Context,
        items: List<RingItem>,
        channelId: String,
        fullScreenIntent: Boolean,
    ): Notification {
        val screen = ringScreen(context)
        val builder =
            Notification
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(screen)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (fullScreenIntent) builder.setFullScreenIntent(screen, true)
        describe(builder, context, items)
        // One occurrence's buttons fit on its notification. With several the screen has each one's own.
        if (items.size == 1) addActions(builder, context, items.single())
        return builder.build()
    }

    /** A reminder that arrives as a notification and nothing else: Tier 1, or a silent presentation. */
    fun reminder(
        context: Context,
        item: RingItem,
        channelId: String,
        late: Boolean,
    ): Notification {
        val builder =
            Notification
                .Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(if (late) Notification.CATEGORY_REMINDER else Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(ringScreen(context))
        describe(builder, context, listOf(item))
        addActions(builder, context, item)
        if (late) builder.setSubText(context.getString(R.string.notification_late_text))
        return builder.build()
    }

    /** The notification that says her reminders were reset (ADR 0051). */
    fun reset(context: Context): Notification =
        Notification
            .Builder(context, NotificationChannels.CRITICAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_ERROR)
            .setContentTitle(context.getString(R.string.reset_notification_title))
            .setContentText(context.getString(R.string.reset_notification_text))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.reset_notification_text)))
            .setAutoCancel(true)
            .build()

    /**
     * The buttons the domain offered for [item] (ADR 0066), each an immutable explicit broadcast to a receiver that
     * is not exported ([RingActionIntents]). A notification sets no delete intent: swiping it away writes nothing,
     * because dismissing the alert is not completion.
     */
    private fun addActions(
        builder: Notification.Builder,
        context: Context,
        item: RingItem,
    ) {
        val icon = Icon.createWithResource(context, R.drawable.ic_notification)
        for (
        (action, label) in
        listOf(
            OccurrenceAction.ACKNOWLEDGE to R.string.ring_action_acknowledge,
            OccurrenceAction.SNOOZE to R.string.ring_action_snooze,
            OccurrenceAction.SKIP to R.string.ring_action_skip,
        )
        ) {
            if (action !in item.actions) continue
            builder.addAction(
                Notification.Action
                    .Builder(icon, context.getString(label), RingActionIntents.pending(context, item.alarmSlot, action))
                    .build(),
            )
        }
    }

    private fun describe(
        builder: Notification.Builder,
        context: Context,
        items: List<RingItem>,
    ) {
        if (items.size == 1) {
            val item = items.single()
            builder.setContentTitle(
                item.title.ifBlank { context.getString(R.string.notification_reminder_fallback_title) },
            )
            val detail = listOfNotNull(item.dosage, item.doctorInstructions).joinToString("\n")
            if (detail.isNotEmpty()) {
                builder.setContentText(item.dosage ?: item.doctorInstructions)
                builder.setStyle(Notification.BigTextStyle().bigText(detail))
            }
        } else {
            builder.setContentTitle(
                context.resources.getQuantityString(R.plurals.notification_due_title, items.size, items.size),
            )
            builder.setContentText(items.joinToString(", ") { it.title })
        }
    }
}
