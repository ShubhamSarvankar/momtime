package com.momtime.android.di

/**
 * What the device reports as its boot count, for the armed record (decision 6). Read through a seam so a test
 * can change it, and so that `Settings.Global.BOOT_COUNT` is read only here (the android clock check keeps it
 * out of every other package). [UNKNOWN] when the platform does not say.
 *
 * It is the boot count and not `elapsedRealtime` that says the device restarted. Uptime at arm time compared
 * with uptime now misses any restart after which the device has been up longer than it had been when the alarm
 * was armed, which is the ordinary case for a phone left on for days.
 */
internal fun interface BootCount {
    fun read(): Long

    companion object {
        const val UNKNOWN = -1L
    }
}
