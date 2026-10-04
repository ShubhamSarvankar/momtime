package com.momtime.android.di

import android.app.AlarmManager
import android.content.Context
import android.provider.Settings
import com.momtime.android.arming.AlarmApi
import com.momtime.android.arming.AlarmFireHandler
import com.momtime.android.arming.AlarmLog
import com.momtime.android.arming.AlarmProbe
import com.momtime.android.arming.AndroidAlarmScheduler
import com.momtime.android.arming.AppStart
import com.momtime.android.arming.AppVersion
import com.momtime.android.arming.ArmCandidates
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.arming.CapabilityResolver
import com.momtime.android.arming.DeliveryDecider
import com.momtime.android.arming.DeliveryPort
import com.momtime.android.arming.PlatformAlarmApi
import com.momtime.android.arming.PlatformAlarmProbe
import com.momtime.android.arming.PlatformAppVersion
import com.momtime.android.arming.PlatformProbes
import com.momtime.android.arming.RecordingDeliveryPort
import com.momtime.android.arming.Watchdog
import com.momtime.android.capability.PlatformCapabilityReader
import com.momtime.android.delivery.AndroidDeliveryPort
import com.momtime.android.delivery.DeliveryServices
import com.momtime.android.delivery.OverlayLauncher
import com.momtime.android.delivery.PlatformOverlayLauncher
import com.momtime.android.delivery.PlatformRingerLauncher
import com.momtime.android.delivery.RingController
import com.momtime.android.delivery.RingDomain
import com.momtime.android.delivery.RingerLauncher
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.AlarmSound
import com.momtime.android.ringer.AlarmVibration
import com.momtime.android.ringer.MainLooperScheduler
import com.momtime.android.ringer.MediaPlayers
import com.momtime.android.ringer.PlatformVibration
import com.momtime.android.ringer.RingSound
import com.momtime.android.ringer.SoundPlayers
import com.momtime.android.ringer.SoundScheduler
import com.momtime.android.ringer.VolumeRamp
import com.momtime.android.settings.AndroidSettings
import com.momtime.android.work.MaterialisationPass
import com.momtime.android.work.Pass
import com.momtime.android.work.TimeZonePass
import com.momtime.android.work.WorkPasses
import com.momtime.shared.data.DeliveryPolicyCommand
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.OccurrenceActionCommand
import com.momtime.shared.data.ReconcileCommand
import com.momtime.shared.data.TimeZoneChangeCommand
import com.momtime.shared.domain.AlarmScheduler
import kotlinx.datetime.TimeZone
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.UUID

/**
 * The device's current time zone, read only here (invariant 8's companion: no `TimeZone.currentSystemDefault()` and no
 * `java.util.TimeZone.getDefault()` outside the DI packages, enforced by `verifyNoClockSystem`). The time zone change
 * pass asks it; a test supplies the zone it wants.
 */
internal fun interface DeviceZone {
    fun current(): TimeZone
}

internal fun platformBootCount(context: Context) =
    BootCount {
        Settings.Global.getLong(context.contentResolver, Settings.Global.BOOT_COUNT, BootCount.UNKNOWN)
    }

/**
 * What a test may replace in delivery. In production [enabled] is true and everything else is the platform's;
 * with [enabled] false the graph hands fired rungs to a [RecordingDeliveryPort], which is what the tests of the
 * fire path itself want.
 */
@Suppress("LongParameterList")
internal class DeliveryWiring(
    val enabled: Boolean = false,
    val ringer: RingerLauncher? = null,
    val overlay: OverlayLauncher? = null,
    val sound: AlarmSound? = null,
    val vibration: AlarmVibration? = null,
    val players: SoundPlayers? = null,
    val scheduler: SoundScheduler? = null,
    val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    val fullScreenIntentApi: (() -> Boolean)? = null,
)

/**
 * The alarm subsystem's graph (ADR 0053, ADR 0058, ADR 0060): the capability resolver, the scheduler over
 * `AlarmManager`, the one coordinator, the fire path and its delivery, the watchdog and the passes the workers
 * run. The alarm API, the boot count, the alarm probe, the app version and, through [DeliveryWiring], the ringer,
 * the overlay, the sound and the zone are replaceable by tests, and nothing else here is.
 */
@Suppress("LongParameterList")
internal fun armingModule(
    context: Context,
    alarmApi: AlarmApi? = null,
    bootCount: BootCount = platformBootCount(context),
    newId: () -> String = { UUID.randomUUID().toString() },
    probe: AlarmProbe = PlatformAlarmProbe(context),
    appVersion: AppVersion = PlatformAppVersion(context),
    delivery: DeliveryWiring = DeliveryWiring(),
): Module =
    module {
        single {
            CapabilityResolver(
                delivery.fullScreenIntentApi?.let { PlatformCapabilityReader(context, fullScreenIntentApi = it) }
                    ?: PlatformCapabilityReader(context),
            )
        }
        single<AlarmApi> { alarmApi ?: PlatformAlarmApi(context.getSystemService(AlarmManager::class.java)) }
        single<AlarmScheduler> { AndroidAlarmScheduler(context, get(), get(), get()) }
        single { RecordingDeliveryPort() }
        single { RingSessions() }
        single { AndroidSettings(context) }
        single<AlarmSound> {
            delivery.sound
                ?: RingSound(
                    context,
                    delivery.players ?: MediaPlayers(context),
                    delivery.scheduler ?: MainLooperScheduler(),
                    VolumeRamp(),
                ) { get<AndroidSettings>().backupSoundDelay() }
        }
        single<AlarmVibration> { delivery.vibration ?: PlatformVibration(context) }
        single { OccurrenceActionCommand(get(), get(), get(), newId) }
        single<RingerLauncher> { delivery.ringer ?: PlatformRingerLauncher(context) }
        single<OverlayLauncher> { delivery.overlay ?: PlatformOverlayLauncher(context) }
        single { RingDomain(get(), get(), get(), get()) { get<AlarmLog>().now() } }
        single { RingController(context, get(), get(), get(), get(), get(), get()) }
        single { DeliveryServices(get(), get(), get(), get(), get(), get()) { get<AlarmLog>().now() } }
        single<DeliveryPort> {
            if (delivery.enabled) {
                AndroidDeliveryPort(context, get(), get(), get(), get(), get())
            } else {
                get<RecordingDeliveryPort>()
            }
        }
        single { DeliveryPolicyCommand(get(), get(), delivery.zone) }
        single { DeliveryDecider(get(), get()) }
        single { ArmCandidates(get(), get(), get(), get()) }
        single { AlarmLog(get(), get(), newId) }
        single { PlatformProbes(probe, bootCount, appVersion) }
        single { ArmingCoordinator(get(), get(), get(), get(), get(), get()) }
        single { AlarmFireHandler(get(), get(), get(), get(), get(), get()) }
        single { ReconcileCommand(get(), get(), get(), newId) }
        single { MaterialiseCommand(get(), get(), newId) }
        single { Watchdog(get(), get(), get(), get(), get(), get()) }
        single { AppStart(get(), get(), get()) }
        single<DeviceZone> { DeviceZone(delivery.zone) }
        single { TimeZoneChangeCommand(get(), get()) }
        single { TimeZonePass(get(), get(), get(), get(), get(), get()) }
        single {
            WorkPasses(
                get(),
                MaterialisationPass(get(), get()) { get<AlarmLog>().now() },
                system = Pass { get<AppStart>().run() },
                timezone = get<TimeZonePass>(),
            )
        }
    }
