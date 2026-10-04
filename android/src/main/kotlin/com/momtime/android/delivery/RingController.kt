package com.momtime.android.delivery

import android.app.NotificationManager
import android.content.Context
import com.momtime.android.ring.RingHost
import com.momtime.android.ring.RingItem
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.AlarmSound
import com.momtime.android.ringer.RingerHost
import com.momtime.android.store.FireTelemetryRepository

/**
 * What the ring screen and the ringer service reach the rest of the app through (ADR 0062). It owns the one
 * ring session and the one sound, and it is the only thing either of them can call.
 *
 * It writes nothing to the event log, and it holds no repository: the only store it touches is the android
 * store's telemetry, to say what the ringer did. Stopping the sound ends the session and stops the service. That
 * is all: dismissing the alert is not completion (ARCHITECTURE.md section 4.6), so the occurrence keeps its state
 * and the next rung still fires.
 */
internal class RingController(
    private val context: Context,
    override val sessions: RingSessions,
    override val sound: AlarmSound,
    private val ringer: RingerLauncher,
    private val telemetry: FireTelemetryRepository,
) : RingHost,
    RingerHost {
    override fun stopSound() {
        sessions.end()
        ringer.stop()
    }

    override fun items(): List<RingItem> = sessions.items()

    override fun onSoundStarted(
        eventId: String,
        focusObtained: Boolean,
    ) {
        telemetry.recordRinger(eventId, started = true, audioFocus = focusObtained)
    }

    /** The platform refused after the service began: the ring is the notification, and the session ends. */
    override fun onRingerRefused(
        eventId: String,
        channelId: String,
        fullScreenIntent: Boolean,
    ) {
        telemetry.recordRinger(eventId, started = false, audioFocus = null)
        sessions.end()
        context.getSystemService(NotificationManager::class.java).notify(
            RingNotifications.RING_ID,
            RingNotifications.ring(context, sessions.items(), channelId, fullScreenIntent),
        )
    }
}
