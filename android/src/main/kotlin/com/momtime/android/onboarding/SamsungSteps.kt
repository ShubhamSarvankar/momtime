package com.momtime.android.onboarding

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.momtime.android.R

/** How a step was opened. */
enum class StepOpened { DEEP_LINK, FALLBACK, NOTHING }

/**
 * The Samsung One UI walkthrough (ADR 0072): the four settings that decide whether an unopened app keeps its alarms.
 * One UI puts apps to sleep on its own schedule (Sleeping apps, Deep sleeping apps, and "Put unused apps to sleep"),
 * and a battery setting of anything but Unrestricted lets it restrict one more. Each step tries its deep link into
 * Device Care and falls back to a general settings screen if that cannot be started, with a screenshot of what to look
 * for.
 *
 * **Every component name here is unverified until a device check** (`MANUAL_CHECKS.md` P2-34 to P2-37): they are what
 * Samsung's Device Care has been reported to use, and Samsung changes them between One UI versions. That is why each
 * has a fallback, and why a failure to start a deep link is expected, not an error. The walkthrough is shown on a
 * Samsung because the maker says so; that is presentation only, and capability is still resolved at runtime.
 *
 * Starting a component that is not visible to the app throws `ActivityNotFoundException`, and one that is not exported
 * throws `SecurityException`; both are a deep link that does not work, and both fall back.
 */
enum class SamsungStep(
    val title: Int,
    val caption: Int,
    val screenshot: Int,
    val deepLink: ComponentName,
    val fallbackAction: String,
) {
    BATTERY(
        R.string.samsung_battery_title,
        R.string.samsung_battery_caption,
        R.drawable.samsung_step_battery,
        ComponentName(DEVICE_CARE, "com.samsung.android.sm.battery.ui.BatteryActivity"),
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    ),
    SLEEPING_APPS(
        R.string.samsung_sleeping_title,
        R.string.samsung_sleeping_caption,
        R.drawable.samsung_step_sleeping_apps,
        ComponentName(DEVICE_CARE, "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"),
        Settings.ACTION_BATTERY_SAVER_SETTINGS,
    ),
    DEEP_SLEEPING_APPS(
        R.string.samsung_deep_title,
        R.string.samsung_deep_caption,
        R.drawable.samsung_step_deep_sleeping_apps,
        ComponentName(DEVICE_CARE, "com.samsung.android.sm.battery.ui.deepsleeping.DeepSleepingActivity"),
        Settings.ACTION_BATTERY_SAVER_SETTINGS,
    ),
    UNUSED_APPS(
        R.string.samsung_unused_title,
        R.string.samsung_unused_caption,
        R.drawable.samsung_step_unused_apps,
        ComponentName(DEVICE_CARE, "com.samsung.android.sm.battery.ui.setting.BatterySettingActivity"),
        Settings.ACTION_BATTERY_SAVER_SETTINGS,
    ),
    ;

    /** The deep link's intent. */
    fun deepLinkIntent(): Intent = Intent().setComponent(deepLink)

    /** The general settings screen to fall back to. */
    fun fallbackIntent(): Intent = Intent(fallbackAction)

    /**
     * Opens the step: the deep link, or, if it cannot be started, the general settings. If neither can, nothing opens
     * and the screenshot and caption are all she has. Never throws.
     */
    fun open(context: Context): StepOpened =
        if (start(context, deepLinkIntent())) {
            StepOpened.DEEP_LINK
        } else if (start(context, fallbackIntent())) {
            StepOpened.FALLBACK
        } else {
            StepOpened.NOTHING
        }

    private fun start(
        context: Context,
        intent: Intent,
    ): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
}

private const val DEVICE_CARE = "com.samsung.android.lool"
