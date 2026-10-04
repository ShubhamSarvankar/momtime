package com.momtime.android

import android.app.Application
import com.momtime.android.arming.AlarmFireHandler
import com.momtime.android.arming.ArmingEntryPoint
import com.momtime.android.di.momTimeModules
import org.koin.core.context.startKoin

/**
 * Starts the graph once, when the process starts, whatever started it: an alarm, a job or the user. The
 * receiver finds the fire path through [ArmingEntryPoint], so an alarm that wakes a dead process finds its
 * database and its coordinator ready.
 */
class MomTimeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin { modules(momTimeModules(this@MomTimeApplication)) }.koin
        ArmingEntryPoint.provider = { koin.get<AlarmFireHandler>() }
    }
}
