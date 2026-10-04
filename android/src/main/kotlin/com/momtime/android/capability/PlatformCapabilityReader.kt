package com.momtime.android.capability

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.momtime.android.delivery.NotificationChannels

/**
 * Reads each capability input from the platform, one input per function, so each can be tested against
 * a shadow on its own and none is inferred from another (ADR 0050, ADR 0019). It only reads: what to do
 * with the answers is `resolveDelivery`.
 *
 * It never calls an API that does not exist at the running SDK. `canScheduleExactAlarms` exists from
 * API 31 and `canUseFullScreenIntent` from API 34; below those a guard stands in front of the call, and
 * a test at each SDK shows the call is not made.
 *
 * Two inputs are read through a seam. [criticalChannelBlocked] is one: it reads the Critical notification channel
 * (`NotificationChannels.isCriticalBlocked`), which the user can block while notifications stay on
 * (ADR 0050, ADR 0060).
 * [fullScreenIntentApi] is the other: Robolectric has no shadow for
 * `canUseFullScreenIntent()`, and its real call is a constant false there that no test can change. The seam
 * defaults to the real call, guarded by the SDK check, and a test replaces it to show the value flows
 * through; what a real device reports is `MANUAL_CHECKS.md` P2-10.
 */
class PlatformCapabilityReader(
    private val context: Context,
    private val criticalChannelBlocked: () -> Boolean = { NotificationChannels.isCriticalBlocked(context) },
    private val fullScreenIntentApi: () -> Boolean = {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    },
) {
    fun read(): CapabilityInputs =
        CapabilityInputs(
            exactAlarm = exactAlarm(),
            fullScreenIntent = fullScreenIntent(),
            notificationsEnabled = notificationsEnabled(),
            batteryExempt = batteryExempt(),
            overlayAllowed = overlayAllowed(),
            criticalChannelAllowed = !criticalChannelBlocked(),
        )

    /** `canScheduleExactAlarms()` from API 31. Below that exact alarms need no permission, so true. */
    fun exactAlarm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * `canUseFullScreenIntent()` from API 34. Below that the permission is a normal one granted at
     * install, so it is whether `USE_FULL_SCREEN_INTENT` is declared.
     */
    fun fullScreenIntent(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            fullScreenIntentApi()
        } else {
            declares(FULL_SCREEN_INTENT_PERMISSION)
        }

    fun notificationsEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun batteryExempt(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun overlayAllowed(): Boolean = Settings.canDrawOverlays(context)

    @Suppress("DEPRECATION")
    private fun declares(permission: String): Boolean =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .contains(permission)

    private companion object {
        const val FULL_SCREEN_INTENT_PERMISSION = "android.permission.USE_FULL_SCREEN_INTENT"
    }
}
