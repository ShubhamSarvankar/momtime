package com.momtime.android

import androidx.work.testing.WorkManagerTestInitHelper

/**
 * The real application, with `WorkManager` started the way a test can: Robolectric does not run the default
 * initializer that starts it in the app, so the test `WorkManager` is initialised before the jobs are enqueued.
 */
class WorkReadyApplication : MomTimeApplication() {
    override fun startWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(this)
        super.startWork()
    }
}
