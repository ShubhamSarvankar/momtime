package com.momtime.android.di

import android.app.AlarmManager
import android.content.Context
import android.provider.Settings
import com.momtime.android.arming.AlarmApi
import com.momtime.android.arming.AlarmFireHandler
import com.momtime.android.arming.AlarmLog
import com.momtime.android.arming.AndroidAlarmScheduler
import com.momtime.android.arming.ArmCandidates
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.arming.CapabilityResolver
import com.momtime.android.arming.DeliveryPort
import com.momtime.android.arming.PlatformAlarmApi
import com.momtime.android.arming.RecordingDeliveryPort
import com.momtime.android.capability.PlatformCapabilityReader
import com.momtime.shared.domain.AlarmScheduler
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.UUID

private fun platformBootCount(context: Context) =
    BootCount { Settings.Global.getLong(context.contentResolver, Settings.Global.BOOT_COUNT, -1L) }

/**
 * The alarm subsystem's graph (ADR 0053): the capability resolver, the scheduler over `AlarmManager`, the
 * one coordinator and the fire path. Delivery is a recording port until PR 5. The alarm API and the boot
 * count are replaceable by tests, and nothing else here is.
 */
internal fun armingModule(
    context: Context,
    alarmApi: AlarmApi? = null,
    bootCount: BootCount = platformBootCount(context),
    newId: () -> String = { UUID.randomUUID().toString() },
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
    }
