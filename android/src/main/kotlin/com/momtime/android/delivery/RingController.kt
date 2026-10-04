package com.momtime.android.delivery

import android.app.NotificationManager
import android.content.Context
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.ring.RingHost
import com.momtime.android.ring.RingItem
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.AlarmSound
import com.momtime.android.ringer.AlarmVibration
import com.momtime.android.ringer.RingerHost
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.data.OccurrenceActionCommand
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.engine.OccurrenceAction
import kotlin.time.Instant

/** What the controller dispatches her actions through: the domain's command, and the one `ensureArmed` entry point. */
internal class RingDomain(
    val actions: OccurrenceActionCommand,
    val coordinator: ArmingCoordinator,
    val occurrences: OccurrenceRepository,
    val templates: ScheduleTemplateRepository,
    val now: () -> Instant,
)

/**
 * What the ring screen, the ringer service and the notification buttons reach the rest of the app through
 * (ADR 0062, ADR 0066). It owns the one ring session and the one sound, and it is the only thing any of them can
 * call.
 *
 * **Her actions.** Acknowledge, snooze and skip are dispatched to the domain ([OccurrenceActionCommand]), which
 * writes the action's own event and changes the occurrence's state in one transaction and nothing else
 * (invariant 3). The controller then calls `ensureArmed` (inside the same exclusion the fire path and the watchdog
 * use, so neither ever reads the half done state), which arms what is next: for a snooze, the snooze's end. Then it
 * takes the occurrence out of the ring session and cancels its notification. Acting on one occurrence leaves the
 * others ringing; when the last one in the session is acted on the session ends and the sound and the vibration
 * stop. An action the domain refuses is refused visibly: the offered actions are refreshed, on the screen and on
 * the notification, so a button that no longer works is gone, never silently ignored.
 *
 * **Stopping the sound** ends the session and stops the service, and writes nothing: dismissing the alert is not
 * completion (ARCHITECTURE.md section 4.6), so the occurrence keeps its state and the next rung still fires.
 * Nothing the screen or a notification does on dismissal writes an event.
 */
@Suppress("LongParameterList")
internal class RingController(
    private val context: Context,
    override val sessions: RingSessions,
    override val sound: AlarmSound,
    override val vibration: AlarmVibration,
    private val ringer: RingerLauncher,
    private val telemetry: FireTelemetryRepository,
    private val domain: RingDomain,
) : RingHost,
    RingerHost,
    NotificationActionHandler {
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val actions = RingActionDispatcher(context, sessions, ringer, domain)

    override fun stopSound() {
        sessions.end()
        ringer.stop()
        notifications.cancel(RingNotifications.RING_ID)
    }

    override fun items(): List<RingItem> = sessions.items()

    override fun act(
        occurrenceId: String,
        action: OccurrenceAction,
    ) {
        actions.dispatch(occurrenceId, action)
    }

    /** A notification button names its occurrence by slot, never by id. An unknown slot does nothing. */
    override fun act(
        slot: Int,
        action: OccurrenceAction,
    ) {
        domain.occurrences.findByAlarmSlot(slot)?.let { actions.dispatch(it.id, action) }
    }

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
        notifications.notify(
            RingNotifications.RING_ID,
            RingNotifications.ring(context, sessions.items(), channelId, fullScreenIntent),
        )
    }
}
