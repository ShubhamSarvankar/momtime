package com.momtime.android.onboarding

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import com.momtime.android.R

/** The permission screen's text, and the one place a settings screen is started (ADR 0072). */
internal object SetupText {
    fun tier(state: SetupState): Int =
        when (state.tier.ordinal) {
            0 -> R.string.setup_tier_1
            1 -> R.string.setup_tier_2
            else -> R.string.setup_tier_3
        }

    fun title(flow: PermissionFlow): Int =
        when (flow) {
            PermissionFlow.NOTIFICATIONS -> R.string.setup_notifications
            PermissionFlow.EXACT_ALARM -> R.string.setup_exact_alarm
            PermissionFlow.FULL_SCREEN_INTENT -> R.string.setup_full_screen
            PermissionFlow.BATTERY -> R.string.setup_battery
            PermissionFlow.OVERLAY -> R.string.setup_overlay
            PermissionFlow.UNUSED_APP_RESTRICTIONS -> R.string.setup_unused_app
        }

    fun status(item: FlowItem): Int =
        when {
            !item.needed -> R.string.setup_status_ok
            item.launch == null -> R.string.setup_status_cannot
            else -> R.string.setup_status_needed
        }

    /** A settings screen that cannot be started falls back to the general settings, and then to nothing. */
    fun openSettings(
        activity: Activity,
        intent: Intent,
    ) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // Nothing on this phone can show a settings screen; the row stays and says what is needed.
            }
        }
    }
}
