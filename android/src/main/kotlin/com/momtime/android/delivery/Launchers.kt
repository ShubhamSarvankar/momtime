package com.momtime.android.delivery

import android.content.Context
import android.content.Intent
import com.momtime.android.ring.RingActivity
import com.momtime.android.ringer.RingerService

/** What the ringer service is asked to do, and with what. */
internal data class RingerRequest(
    val channelId: String,
    val fullScreenIntent: Boolean,
    val eventId: String,
)

/**
 * Starts and stops the ringer service. The platform may refuse the start (`ForegroundServiceStartNotAllowedException`,
 * a `SecurityException`, any other refusal), so [start] may throw and its caller, the delivery port, catches that
 * and degrades to the notification path (ADR 0061). It is a seam so that a test can refuse.
 */
internal interface RingerLauncher {
    fun start(request: RingerRequest)

    fun stop()
}

internal class PlatformRingerLauncher(
    private val context: Context,
) : RingerLauncher {
    override fun start(request: RingerRequest) {
        context.startForegroundService(RingerService.startIntent(context, request))
    }

    override fun stop() {
        context.stopService(Intent(context, RingerService::class.java))
    }
}

/**
 * The overlay route (ADR 0061): with `SYSTEM_ALERT_WINDOW` granted the app may start an activity from the
 * background, which is how the ring screen is opened when a full screen intent is not available. It is a way to
 * show the screen and never the way the alarm rings; the ringer service is.
 */
internal fun interface OverlayLauncher {
    fun launch()
}

internal class PlatformOverlayLauncher(
    private val context: Context,
) : OverlayLauncher {
    override fun launch() {
        context.startActivity(RingActivity.intent(context))
    }
}
