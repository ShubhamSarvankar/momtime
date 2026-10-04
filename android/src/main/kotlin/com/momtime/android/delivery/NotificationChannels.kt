package com.momtime.android.delivery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.momtime.android.R
import com.momtime.shared.domain.Criticality

/**
 * The notification channels (ADR 0060, ARCHITECTURE.md section 5.6): three, split by criticality and never by
 * task type, so that she can tune what matters and onboarding can say which one not to mute.
 *
 * - **Critical** and **Standard** are importance high, because a heads up notification and a full screen intent
 *   both need it. Critical carries the alarm tone on the alarm stream, so a Tier 1 reminder, which has no ringer
 *   behind it, is still heard; Standard carries the default notification sound.
 * - **Gentle** is importance low: no sound, no heads up. It is also where every silent presentation goes, a
 *   rung beyond the catch up window and one held back by quiet hours or the interruption budget, whatever the
 *   criticality of its occurrence, because a notification cannot be made silent on a channel that sounds.
 *
 * A channel's importance is the user's once it exists: the app can create it and cannot raise it again. So a
 * user can block one channel while notifications stay on, and [isCriticalBlocked] is the input that makes
 * capability resolution say so (ADR 0050).
 */
internal object NotificationChannels {
    const val CRITICAL = "momtime.critical"
    const val STANDARD = "momtime.standard"
    const val GENTLE = "momtime.gentle"

    /** The channel a reminder of [criticality] is presented on when it is allowed to make a sound. */
    fun idFor(criticality: Criticality): String =
        when (criticality) {
            Criticality.CRITICAL -> CRITICAL
            Criticality.STANDARD -> STANDARD
            Criticality.GENTLE -> GENTLE
        }

    /** Creates the three channels. Creating one that exists changes nothing the user has set. */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        manager.createNotificationChannel(
            NotificationChannel(
                CRITICAL,
                context.getString(R.string.channel_critical_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_critical_description)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarm)
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                STANDARD,
                context.getString(R.string.channel_standard_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_standard_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                GENTLE,
                context.getString(R.string.channel_gentle_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_gentle_description)
                setSound(null, null)
            },
        )
    }

    /**
     * True if the user has blocked the Critical channel (importance none). A channel that does not exist yet is
     * not blocked: nothing has been decided about it.
     */
    fun isCriticalBlocked(context: Context): Boolean =
        context
            .getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CRITICAL)
            ?.importance == NotificationManager.IMPORTANCE_NONE
}
