package com.momtime.android.ringer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.momtime.android.delivery.RingNotifications
import com.momtime.android.delivery.RingerRequest
import com.momtime.android.ring.RingItem

/** What the ringer service needs from the rest of the app. Set when the application starts. */
interface RingerHost {
    val sound: AlarmSound

    /** The vibration that goes with the sound, stopped with it. */
    val vibration: AlarmVibration

    /** The occurrences the ring session covers now. */
    fun items(): List<RingItem>

    /**
     * True once the session has spread (ADR 0080): each occurrence's actions are on a notification of its own, so
     * the ring notification carries none and does not alert again when it is rebuilt.
     */
    val spread: Boolean

    /** The sound started for the fire [eventId]; [focusObtained] is what audio focus said. */
    fun onSoundStarted(
        eventId: String,
        focusObtained: Boolean,
    )

    /** The platform refused to run the service as a foreground service, so the ring degrades to a notification. */
    fun onRingerRefused(
        eventId: String,
        channelId: String,
        fullScreenIntent: Boolean,
    )
}

object RingerEntryPoint {
    @Volatile
    var provider: (() -> RingerHost)? = null
}

/**
 * The ringer (ADR 0061, ADR 0065): a foreground service of type `mediaPlayback`, started from the fire path under
 * the exact alarm exemption, that plays the alarm sound, and vibrates with the template's pattern, until it is
 * stopped. It is not the alarm: `setAlarmClock` is,
 * and this is what a fired alarm starts. It writes nothing. Stopping the sound is not completion.
 *
 * Boot never starts it (CLAUDE.md): on Android 15 a boot receiver cannot start a `mediaPlayback` foreground
 * service, and nothing here is reachable from one.
 *
 * If the platform refuses the foreground start, or anything else goes wrong, the service stops itself and tells
 * the host, which degrades to the notification path. It never crashes the process over a refusal.
 *
 * When the ring is dismissed (the cap, "Stop the sound", ADR 0090) the launcher first [detach]es the notification
 * from the service and then stops the service: stopping a foreground service cancels its notification, and the
 * notification must stay, reposted as an ordinary one with her actions. [running] is the instance to detach.
 */
class RingerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("TooGenericExceptionCaught")
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val host = RingerEntryPoint.provider?.invoke()
        if (intent?.action != ACTION_START || host == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        val channelId = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        val fullScreen = intent.getBooleanExtra(EXTRA_FULL_SCREEN, false)
        val vibration =
            intent.getStringExtra(EXTRA_VIBRATION)?.let(VibrationPattern::fromName) ?: VibrationPattern.URGENT
        try {
            startForeground(
                RingNotifications.RING_ID,
                RingNotifications.ring(
                    this,
                    host.items(),
                    channelId,
                    fullScreen,
                    spread = host.spread,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            running = this
            host.onSoundStarted(eventId, host.sound.start())
            host.vibration.start(vibration)
        } catch (e: RuntimeException) {
            Log.e(TAG, "the ringer could not start: ${e.javaClass.simpleName}")
            running = null
            host.onRingerRefused(eventId, channelId, fullScreen)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * Leaves the foreground and leaves the notification where it is (`STOP_FOREGROUND_DETACH`), so that stopping the
     * service afterwards does not cancel it. The controller then reposts it as an ordinary one (ADR 0090 item 3).
     */
    fun detach() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onDestroy() {
        if (running === this) running = null
        val host = RingerEntryPoint.provider?.invoke()
        host?.sound?.stop()
        host?.vibration?.stop()
        super.onDestroy()
    }

    companion object {
        /** The instance in the foreground, or null: set when it goes foreground, cleared when it is destroyed. */
        @Volatile
        var running: RingerService? = null
            private set

        const val ACTION_START = "com.momtime.android.action.RING_START"
        private const val EXTRA_CHANNEL = "com.momtime.android.extra.CHANNEL"
        private const val EXTRA_FULL_SCREEN = "com.momtime.android.extra.FULL_SCREEN"
        private const val EXTRA_EVENT_ID = "com.momtime.android.extra.EVENT_ID"
        private const val EXTRA_VIBRATION = "com.momtime.android.extra.VIBRATION"
        private const val TAG = "MomTimeRinger"

        internal fun startIntent(
            context: Context,
            request: RingerRequest,
        ): Intent =
            Intent(context, RingerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CHANNEL, request.channelId)
                .putExtra(EXTRA_FULL_SCREEN, request.fullScreenIntent)
                .putExtra(EXTRA_EVENT_ID, request.eventId)
                .putExtra(EXTRA_VIBRATION, request.vibration.name)
    }
}
