package com.momtime.android.delivery

import android.app.NotificationManager
import android.content.Context
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.arming.SessionSweep
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
import com.momtime.shared.domain.Occurrence
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
 * **Dismissal** (ADR 0090): "Stop the sound" and the cap are one path. The session ends and the service stops, so
 * the sound and the vibration stop and the service leaves the foreground; the session's notification is reposted as
 * an ordinary one that keeps her actions (not ongoing, no full screen intent, only alert once); and nothing is
 * written: dismissing the alert is not completion (ARCHITECTURE.md section 4.6), so the occurrence keeps its state
 * and the next rung still fires, and rings again as a ring of its own. The cap's dismissal also marks the fire it
 * ran from as a ring that ran out unanswered (ADR 0090 item 8); "Stop the sound" marks nothing.
 *
 * **The end of grace ends a ring** (ADR 0090 item 7): [dropClosed], called after every `Reconcile` android
 * dispatches, drops every listed occurrence that is no longer open, cancels its notification, and ends the ring if
 * none is left.
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
    private val cap: RingCap,
) : RingHost,
    RingerHost,
    NotificationActionHandler,
    SessionSweep {
    private val notifications: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val actions = RingActionDispatcher(context, sessions, ringer, cap, domain)

    /** The one dismissal path (ADR 0090 item 3): "Stop the sound", and the cap through [capExpired]. */
    override fun stopSound() {
        cap.cancel()
        val wasSounding = sessions.isActive
        sessions.end()
        ringer.stop()
        // The ring notification was the service's; it is reposted as an ordinary one with her actions, or left as it
        // was when nothing was sounding (the screen's button is not shown then).
        if (wasSounding) SessionNotifications.refresh(context, sessions)
    }

    /** The cap ran out for the fire [eventId]: the same dismissal, and the fire's row is marked (ADR 0090 item 8). */
    internal fun capExpired(eventId: String) {
        if (!sessions.isActive) return
        stopSound()
        telemetry.recordRingEndedByCap(eventId)
    }

    override fun dropClosed() {
        val closed =
            sessions.items().filter { item ->
                domain.occurrences.findById(item.occurrenceId)?.isTerminal != false
            }
        if (closed.isEmpty()) return
        var ended = false
        closed.forEach { item ->
            notifications.cancel(item.alarmSlot)
            if (sessions.resolve(item.occurrenceId).ended) ended = true
        }
        if (ended) {
            cap.cancel()
            ringer.stop()
        }
        SessionNotifications.refresh(context, sessions)
    }

    /**
     * A template edit withdrew [occurrences] (ADR 0079 item 8). [dropClosed] finds them, because a withdrawn
     * occurrence is terminal: it drops each from the ring session, cancels the session's notification when nothing
     * is left and ends the ring when the sound was on. What it cannot do is reach a notification that is not in the
     * session (a plain one at Tier 1, a Gentle one, a silent notice, each posted under the occurrence's slot), so
     * each withdrawn occurrence's slot notification is cancelled here.
     */
    override fun withdrawn(occurrences: List<Occurrence>) {
        occurrences.forEach { notifications.cancel(it.alarmSlot) }
        dropClosed()
    }

    /**
     * A template edit changed what is shown (ADR 0079 item 4): every listed occurrence of [templateId] takes the
     * template's title, dosage and instructions as they are now, which the screen renders at once, and the
     * session's notification is replaced under its id with the same actions and without alerting again. The ring
     * is not touched: the sound, the cap and the service are as they were, and nothing is written.
     */
    override fun edited(templateId: String) {
        val template = domain.templates.findById(templateId) ?: return
        val shown =
            sessions.items().filter { item ->
                domain.occurrences.findById(item.occurrenceId)?.templateId == templateId
            }
        if (shown.isEmpty()) return
        shown.forEach {
            sessions.describe(it.occurrenceId, template.title, template.dosage, template.doctorInstructions)
        }
        SessionNotifications.refresh(context, sessions, alertOnce = true)
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
        cap.cancel()
        sessions.end()
        notifications.notify(
            RingNotifications.RING_ID,
            RingNotifications.ring(context, sessions.items(), channelId, fullScreenIntent),
        )
    }
}
