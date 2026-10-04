package com.momtime.android.delivery

import android.app.NotificationManager
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.momtime.android.arming.CapabilityResolver
import com.momtime.android.arming.DeliveryPort
import com.momtime.android.arming.FiredRung
import com.momtime.android.arming.PlatformProbes
import com.momtime.android.arming.Presentation
import com.momtime.android.ring.RingItem
import com.momtime.android.ring.RingJoin
import com.momtime.android.ring.RingSessions
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.ScheduleTemplate

/**
 * Delivery: everything from a fire to what she sees and hears (ADR 0060, ADR 0061, ADR 0062). The fire path has
 * decided how the rung is presented and what the domain says (a ring, or silent under quiet hours or the budget),
 * and whether a ring is already going. This chooses the path from that and from the capability resolution
 * ([DeliveryPath.choose]) and takes it:
 *
 * - **RING**, **HEADS_UP**, **AUDIO_ONLY**: the rung joins the ring session. The first occurrence starts the
 *   ringer service, which plays the sound and holds the ring notification; another occurrence joins the session
 *   and only refreshes the notification; the same occurrence again continues it and changes nothing. If the
 *   platform refuses the ringer, the same notification is posted instead and the session ends, so the next rung
 *   tries again, and the refusal is recorded. Never a crash. Without a full screen intent, and with the overlay
 *   permission, the ring screen is opened through the overlay route.
 * - **PLAIN**: Tier 1. A notification on the criticality channel, replacing the one for the same occurrence.
 * - **SILENT_NOTICE**, **SILENT**: a silent notification on the Gentle channel.
 *
 * A row of device telemetry is written first (the android store, never fatal), then updated by what the ringer
 * did. Nothing here writes to the event log or changes an occurrence.
 */
internal class AndroidDeliveryPort(
    private val context: Context,
    private val resolver: CapabilityResolver,
    private val occurrences: OccurrenceRepository,
    private val templates: ScheduleTemplateRepository,
    private val sessions: RingSessions,
    private val services: DeliveryServices,
) : DeliveryPort {
    override fun deliver(
        rung: FiredRung,
        eventId: String,
    ) {
        NotificationChannels.ensure(context)
        val resolution = resolver.current
        val path = DeliveryPath.choose(rung, resolution)
        val occurrence = occurrences.findById(rung.occurrenceId) ?: return
        val template = templates.findById(occurrence.templateId) ?: return
        val item = RingItem(occurrence.id, template.title, template.dosage, template.doctorInstructions)
        services.telemetry.insert(telemetryRow(eventId, resolution.capability, path))
        val manager = context.getSystemService(NotificationManager::class.java)
        when (path) {
            DeliveryPath.PLAIN ->
                manager.notify(
                    rung.alarmSlot,
                    RingNotifications.reminder(
                        context,
                        item,
                        NotificationChannels.idFor(template.criticality),
                        late = false,
                    ),
                )
            DeliveryPath.SILENT_NOTICE, DeliveryPath.SILENT ->
                manager.notify(
                    rung.alarmSlot,
                    RingNotifications.reminder(
                        context,
                        item,
                        NotificationChannels.GENTLE,
                        late = rung.presentation == Presentation.SILENT_NOTICE,
                    ),
                )
            DeliveryPath.RING, DeliveryPath.HEADS_UP, DeliveryPath.AUDIO_ONLY ->
                ring(path, item, template, eventId, resolution.overlayAvailable)
        }
    }

    private fun ring(
        path: DeliveryPath,
        item: RingItem,
        template: ScheduleTemplate,
        eventId: String,
        overlayAvailable: Boolean,
    ) {
        val channel = NotificationChannels.idFor(template.criticality)
        val fullScreen = path == DeliveryPath.RING
        when (sessions.join(item)) {
            RingJoin.CONTINUED -> Unit
            RingJoin.JOINED ->
                context.getSystemService(NotificationManager::class.java).notify(
                    RingNotifications.RING_ID,
                    RingNotifications.ring(context, sessions.items(), channel, fullScreen),
                )
            RingJoin.STARTED -> startRinger(RingerRequest(channel, fullScreen, eventId), path, overlayAvailable)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startRinger(
        request: RingerRequest,
        path: DeliveryPath,
        overlayAvailable: Boolean,
    ) {
        try {
            services.ringer.start(request)
        } catch (_: RuntimeException) {
            // The platform refused (ForegroundServiceStartNotAllowedException, a SecurityException, any other
            // refusal): the ring degrades to the notification, the refusal is recorded, and the session ends so
            // that the next rung tries the ringer again. Nothing here may crash the fire path.
            services.telemetry.recordRinger(request.eventId, started = false, audioFocus = null)
            context.getSystemService(NotificationManager::class.java).notify(
                RingNotifications.RING_ID,
                RingNotifications.ring(context, sessions.items(), request.channelId, request.fullScreenIntent),
            )
            sessions.end()
        }
        // The overlay route opens the ring screen when a full screen intent will not, and never otherwise.
        if (path != DeliveryPath.RING && overlayAvailable) services.overlay.launch()
    }

    private fun telemetryRow(
        eventId: String,
        capability: com.momtime.shared.domain.DeliveryCapability,
        path: DeliveryPath,
    ): FireTelemetry {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return FireTelemetry(
            eventId = eventId,
            resolvedTier = capability,
            screenOn = power.isInteractive,
            audioFocusObtained = null,
            batteryPct = level.takeIf { it in 0..PERCENT_MAX },
            dozeState = if (power.isDeviceIdleMode) "IDLE" else "ACTIVE",
            watchdogRepair = false,
            bootCount =
                services.probes.bootCount
                    .read()
                    .takeIf { it >= 0 },
            deliveryPath = path.name,
            ringerStarted = null,
        )
    }

    private companion object {
        const val PERCENT_MAX = 100
    }
}

/** What the port reaches the platform and the store through, each replaceable by a test. */
internal class DeliveryServices(
    val ringer: RingerLauncher,
    val overlay: OverlayLauncher,
    val telemetry: FireTelemetryRepository,
    val probes: PlatformProbes,
)
