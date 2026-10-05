package com.momtime.android.di

import android.os.SystemClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long the device has been up, including time asleep (`elapsedRealtime`). With the wall clock it gives the instant
 * the boot began (ADR 0070). Read through a seam so a test can move it, and only here.
 */
internal fun interface Uptime {
    fun sinceBoot(): Duration
}

internal fun platformUptime() = Uptime { SystemClock.elapsedRealtime().milliseconds }
