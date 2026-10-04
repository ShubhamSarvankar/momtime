package com.momtime.android.di

/**
 * What the device reports as its boot count, for the armed record (decision 6). Read through a seam so a
 * test can change it; `-1` when the platform does not say.
 */
internal fun interface BootCount {
    fun read(): Long
}
