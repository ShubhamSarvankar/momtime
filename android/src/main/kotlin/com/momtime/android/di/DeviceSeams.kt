package com.momtime.android.di

import android.content.Context
import android.os.Build

/**
 * The maker of the phone, as the platform names it. Read through a seam so a test can say Samsung or not, and
 * only here.
 * Whether to show the Samsung walkthrough is presentation: capability is resolved at runtime, never inferred from it
 * (CLAUDE.md).
 */
internal fun interface DeviceManufacturer {
    fun name(): String

    fun isSamsung(): Boolean = name().equals("samsung", ignoreCase = true)
}

internal fun platformManufacturer() = DeviceManufacturer { Build.MANUFACTURER.orEmpty() }

/**
 * Whether Android's own unused app restrictions are off for this app (ADR 0072): "pause app activity if unused" can
 * revoke an app's permissions and stop it when it has not been opened for months, and reminders delivered by
 * notification may not count as use. True if she has exempted the app, false if the restriction applies, and null
 * below API 30, where the setting does not exist. `PackageManager.isAutoRevokeWhitelisted` is a framework call from
 * API 30, so no library is needed. Read through a seam so a test can set it.
 */
internal fun interface UnusedAppRestrictions {
    fun isExempt(): Boolean?
}

internal fun platformUnusedAppRestrictions(context: Context) =
    UnusedAppRestrictions {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.packageManager.isAutoRevokeWhitelisted else null
    }
