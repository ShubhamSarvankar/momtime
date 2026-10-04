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
import com.momtime.android.arming.AppUpdate
import com.momtime.android.arming.ArmCandidates
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.arming.CapabilityResolver
import com.momtime.android.arming.DeliveryPort
import com.momtime.android.arming.PlatformAlarmApi
import com.momtime.android.arming.PlatformAlarmProbe
import com.momtime.android.arming.PlatformAppUpdate
import com.momtime.android.arming.PlatformProbes
import com.momtime.android.arming.RecordingDeliveryPort
import com.momtime.android.arming.Watchdog
import com.momtime.android.capability.PlatformCapabilityReader
import com.momtime.android.work.MaterialisationPass
import com.momtime.android.work.WorkPasses
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.ReconcileCommand
import com.momtime.shared.domain.AlarmScheduler
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.UUID

internal fun platformBootCount(context: Context) =
    BootCount {
        Settings.Global.getLong(context.contentResolver, Settings.Global.BOOT_COUNT, BootCount.UNKNOWN)
    }

/**
 * The alarm subsystem's graph (ADR 0053, ADR 0058): the capability resolver, the scheduler over `AlarmManager`,
 * the one coordinator, the fire path, the watchdog and the passes the workers run. Delivery is a recording port
 * until PR 5. The alarm API, the boot count, the alarm probe and the app update time are replaceable by tests,
 * and nothing else here is.
 */
@Suppress("LongParameterList")
internal fun armingModule(
    context: Context,
    alarmApi: AlarmApi? = null,
    bootCount: BootCount = platformBootCount(context),
    newId: () -> String = { UUID.randomUUID().toString() },
    probe: AlarmProbe = PlatformAlarmProbe(context),
    appUpdate: AppUpdate = PlatformAppUpdate(context),
): Module =
    module {
        single { CapabilityResolver(PlatformCapabilityReader(context)) }
        single<AlarmApi> { alarmApi ?: PlatformAlarmApi(context.getSystemService(AlarmManager::class.java)) }
        single<AlarmScheduler> { AndroidAlarmScheduler(context, get(), get(), get()) }
        single { RecordingDeliveryPort() }
        single<DeliveryPort> { get<RecordingDeliveryPort>() }
        single { ArmCandidates(get(), get(), get()) }
        single { AlarmLog(get(), get(), newId) }
        single { ArmingCoordinator(get(), get(), get(), get(), get()) { bootCount.read() } }
        single { AlarmFireHandler(get(), get(), get(), get()) }
        single { ReconcileCommand(get(), get(), get(), newId) }
        single { MaterialiseCommand(get(), get(), newId) }
        single { Watchdog(get(), get(), get(), get(), get(), PlatformProbes(probe, bootCount, appUpdate)) }
        single { AppStart(get(), get(), get()) }
        single { WorkPasses(get(), MaterialisationPass(get(), get()) { get<AlarmLog>().now() }) }
    }
