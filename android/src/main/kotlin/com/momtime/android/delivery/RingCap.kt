package com.momtime.android.delivery

import com.momtime.android.ringer.RingPolicy
import com.momtime.android.ringer.SoundScheduler

/**
 * The cap's timer (ADR 0090 item 5): one timer, on the ringer's own scheduler (the one the backup sound uses), that
 * reads no clock and arms no alarm. Every fire that rings restarts it, whether the fire started a session, continued
 * one or joined one, so a session sounds for up to `MAX_RING` from the latest fire that rang. The session's end by
 * her hand (an action on the last occurrence, "Stop the sound", a refused ringer, a closed occurrence dropped) cancels
 * it. When it expires it hands the id of the fire it was restarted by to [onExpire], which is the controller's cap
 * dismissal: that fire's telemetry row is the one marked as a ring that ran out (ADR 0090 item 8).
 *
 * If the process dies the timer dies with it, and so does the sound (ADR 0062).
 */
internal class RingCap(
    private val scheduler: SoundScheduler,
    private val onExpire: (eventId: String) -> Unit,
) {
    private val lock = Any()
    private var cancellation: (() -> Unit)? = null
    private var armedFor: String? = null

    /** The id of the fire the running timer was restarted by, or null when no timer runs. */
    val armedForEvent: String? get() = synchronized(lock) { armedFor }

    /** Starts the cap afresh for the fire [eventId], replacing a timer that is running. */
    fun restart(eventId: String) {
        synchronized(lock) {
            cancellation?.invoke()
            armedFor = eventId
            cancellation = scheduler.postDelayed(RingPolicy.MAX_RING.inWholeMilliseconds) { expired(eventId) }
        }
    }

    /** Cancels the running timer, if any. The session ended for another reason, so the cap has nothing to end. */
    fun cancel() {
        synchronized(lock) {
            cancellation?.invoke()
            cancellation = null
            armedFor = null
        }
    }

    private fun expired(eventId: String) {
        synchronized(lock) {
            // A timer that was replaced or cancelled after it was posted does nothing.
            if (armedFor != eventId) return
            cancellation = null
            armedFor = null
        }
        onExpire(eventId)
    }
}
