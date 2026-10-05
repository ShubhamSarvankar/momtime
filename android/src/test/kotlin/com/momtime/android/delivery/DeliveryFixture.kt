package com.momtime.android.delivery

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.FireOutcome
import com.momtime.android.arming.t0
import com.momtime.android.di.DeliveryWiring
import com.momtime.android.ringer.AlarmSound
import com.momtime.android.ringer.AlarmVibration
import com.momtime.android.ringer.VibrationPattern
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.data.AppSettingsRepository
import com.momtime.shared.data.InterruptionBudgetRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSettings
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A ringer that records what it was asked and can be made to refuse. */
internal class FakeRinger : RingerLauncher {
    val starts = mutableListOf<RingerRequest>()
    var stops = 0
        private set

    /** What [start] throws, if anything: the platform's refusal. */
    var refusal: RuntimeException? = null

    override fun start(request: RingerRequest) {
        refusal?.let { throw it }
        starts += request
    }

    override fun stop() {
        stops++
    }
}

internal class FakeOverlay : OverlayLauncher {
    var launches = 0
        private set

    override fun launch() {
        launches++
    }
}

internal class FakeSound : AlarmSound {
    var starts = 0
        private set
    var stops = 0
        private set
    var focus = true

    /** What [start] throws, if anything. */
    var failure: RuntimeException? = null

    override fun start(): Boolean {
        failure?.let { throw it }
        starts++
        return focus
    }

    override fun stop() {
        stops++
    }
}

/** A vibration that records what it was asked, with no motor. */
internal class FakeVibration : AlarmVibration {
    val starts = mutableListOf<VibrationPattern>()
    var stops = 0
        private set

    override fun start(pattern: VibrationPattern) {
        starts += pattern
    }

    override fun stop() {
        stops++
    }
}

/**
 * The production graph with the real delivery port over a real database, the shadowed platform, and fakes for the
 * three things a test cannot have (the ringer service launch, the overlay, the speaker). The capability inputs are
 * driven through the platform's own shadows, the way PR 2's tests do.
 */
internal class DeliveryFixture(
    val context: Context,
    fullScreenIntent: Boolean = true,
) {
    val ringer = FakeRinger()
    val overlay = FakeOverlay()
    val sound = FakeSound()
    val vibration = FakeVibration()
    private var fullScreen = fullScreenIntent

    /** The zone the device is in. A zone change test moves it, then delivers the broadcast. */
    var deviceZone: TimeZone = TimeZone.UTC

    val arming =
        ArmingFixture(
            context,
            delivery =
                DeliveryWiring(
                    enabled = true,
                    ringer = ringer,
                    overlay = overlay,
                    sound = sound,
                    vibration = vibration,
                    zone = { deviceZone },
                    fullScreenIntentApi = { fullScreen },
                ),
        )

    val notifications: NotificationManager = context.getSystemService(NotificationManager::class.java)
    val telemetry: FireTelemetryRepository get() = arming.graph.get()
    val settings: AppSettingsRepository get() = arming.graph.get()
    val budget: InterruptionBudgetRepository get() = arming.graph.get()

    init {
        // Everything granted: exact, notifications, battery exempt, no overlay. A test takes things away.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(
            context.getSystemService(PowerManager::class.java),
        ).setIgnoringBatteryOptimizations(context.packageName, true)
        ShadowSettings.setCanDrawOverlays(false)
        settings.ensureSeeded()
    }

    private val declared: List<String> =
        shadowOf(
            context.packageManager,
        ).getInternalMutablePackageInfo(context.packageName).requestedPermissions.orEmpty().toList()

    /**
     * Whether a full screen intent is available. From API 34 the platform says (the seam); below that it is whether
     * `USE_FULL_SCREEN_INTENT` is declared, so the declaration is edited as well, as PR 2's tests do.
     */
    fun fullScreenIntent(allowed: Boolean) {
        fullScreen = allowed
        val permission = "android.permission.USE_FULL_SCREEN_INTENT"
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).requestedPermissions =
            (if (allowed) declared else declared - permission).toTypedArray()
    }

    fun overlayAllowed(allowed: Boolean) = ShadowSettings.setCanDrawOverlays(allowed)

    fun notificationsEnabled(enabled: Boolean) = shadowOf(notifications).setNotificationsEnabled(enabled)

    /** Seeds one occurrence of a template with [criticality], armed from an hour before it is due. */
    fun due(
        id: String,
        criticality: Criticality,
        scheduled: Instant = t0,
        slot: Int,
    ): Occurrence {
        val occurrence = arming.seed(id, criticality, scheduled, slot)
        val before = arming.clock.now
        arming.clock.now = scheduled - 1.hours
        arming.coordinator.ensureArmed()
        arming.clock.now = before
        return occurrence
    }

    /** Fires the first rung of [occurrence] at [now]. */
    fun fire(
        occurrence: Occurrence,
        now: Instant = occurrence.scheduledInstant,
        rung: Instant = occurrence.scheduledInstant,
    ): FireOutcome {
        arming.clock.now = now
        return arming.handler.onFire(occurrence.alarmSlot, rung)
    }

    /** The device telemetry row of the latest `ALARM_FIRED` of [occurrenceId]. */
    fun telemetryOf(occurrenceId: String): FireTelemetry =
        checkNotNull(telemetry.findForEvent(arming.eventsOf(occurrenceId, EventType.ALARM_FIRED).last().id)) {
            "no telemetry row for the latest fire of $occurrenceId"
        }

    fun posted(id: Int): Notification? = shadowOf(notifications).getNotification(id)

    fun channelOf(id: Int): String? = posted(id)?.channelId

    fun close() = arming.close()

    fun assertNothingRang() {
        assertTrue("the ringer was started: ${ringer.starts}", ringer.starts.isEmpty())
    }
}
