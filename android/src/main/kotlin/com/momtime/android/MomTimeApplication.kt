package com.momtime.android

import android.app.Application
import android.util.Log
import com.momtime.android.arming.AlarmFireHandler
import com.momtime.android.arming.AlarmReceiver
import com.momtime.android.arming.AppStart
import com.momtime.android.arming.ArmingEntryPoint
import com.momtime.android.delivery.NotificationChannels
import com.momtime.android.delivery.RingController
import com.momtime.android.di.momTimeModules
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ringer.RingerEntryPoint
import com.momtime.android.work.Work
import com.momtime.android.work.WorkEntryPoint
import com.momtime.android.work.WorkPasses
import org.koin.core.context.startKoin

/**
 * Starts the graph once, when the process starts, whatever started it: an alarm, a job or the user. The
 * receiver and the workers find their passes through [ArmingEntryPoint] and [WorkEntryPoint], and the ring screen
 * and the ringer service find theirs through [RingEntryPoint] and [RingerEntryPoint], so an alarm or a
 * job that wakes a dead process finds its database and its coordinator ready.
 *
 * Then the start path: the two periodic jobs are enqueued (unique and kept, so enqueueing again resets
 * nothing), and the next alarm is made to exist, off the main thread. After an Auto Backup restore this is the
 * first code that runs, so it is what arms the restored schedule.
 */
open class MomTimeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin { modules(momTimeModules(this@MomTimeApplication)) }.koin
        ArmingEntryPoint.provider = { koin.get<AlarmFireHandler>() }
        NotificationChannels.ensure(this)
        val controller = koin.get<RingController>()
        RingEntryPoint.provider = { controller }
        RingerEntryPoint.provider = { controller }
        WorkEntryPoint.provider = { koin.get<WorkPasses>() }
        startWork()
        AlarmReceiver.executor.execute {
            try {
                koin.get<AppStart>().run()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                Log.e(TAG, "start path failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Enqueues the periodic jobs. `WorkManager` itself starts with its default initializer, before this
     * application's `onCreate` (ADR 0057); Robolectric does not run that initializer, so a test overrides this
     * to start the test `WorkManager` first.
     */
    protected open fun startWork() = Work.scheduleAll(this)

    private companion object {
        const val TAG = "MomTimeStart"
    }
}
