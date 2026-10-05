package com.momtime.android.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.momtime.android.capability.CapabilityInputs
import com.momtime.android.capability.DeliveryResolution

/** What she can be asked to allow, so that a reminder reaches her (ARCHITECTURE.md section 5.2). */
enum class PermissionFlow {
    NOTIFICATIONS,
    EXACT_ALARM,
    FULL_SCREEN_INTENT,
    BATTERY,
    OVERLAY,
    UNUSED_APP_RESTRICTIONS,
}

/** How a flow is launched at this API level. Nothing is a guess: each is the platform's own screen or request. */
sealed interface FlowLaunch {
    /** The runtime permission dialog (`POST_NOTIFICATIONS`, API 33 and above). */
    data class RuntimeRequest(
        val permission: String,
    ) : FlowLaunch

    /** A system settings screen, opened as an intent. */
    data class OpenSettings(
        val intent: Intent,
    ) : FlowLaunch
}

/**
 * One row of the setup screen: the flow, whether it is needed now, and what launching it does at this API level, or
 * null if nothing can be launched here. [launch] does not depend on [needed]: it is what the flow would do.
 */
data class FlowItem(
    val flow: PermissionFlow,
    val needed: Boolean,
    val launch: FlowLaunch?,
)

/**
 * The permission flows (ADR 0072). Each is resolved from the capability inputs as they are now, never from the OS
 * version alone: the version says only which screen exists. Every flow is followed, on return, by resolving capability
 * again and calling `ensureArmed` (`SetupController`).
 *
 * - **Notifications:** a runtime request on API 33 and above, the first time. After a request, or below 33 where
 *   notifications can only have been switched off in settings, the app's notification settings.
 * - **Exact alarms:** `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` on API 31 and 32 only. `USE_EXACT_ALARM` covers 33 and
 *   above, and nothing is needed below 31.
 * - **Full screen intent:** `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` on API 34 and above.
 * - **Battery:** the general battery optimisation settings (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), which
 *   needs no permission. The direct request (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) is not declared: it waits on an
 *   Open Items row that Shubham owns.
 * - **Overlay:** `ACTION_MANAGE_OVERLAY_PERMISSION`, offered only when an effective full screen intent is not
 *   available, because it is the secondary route and not the ringing mechanism (CLAUDE.md).
 * - **Unused app restrictions:** `ACTION_AUTO_REVOKE_PERMISSIONS` on API 30 and above, while "pause app activity if
 *   unused" applies.
 */
object PermissionFlows {
    private const val API_R = 30
    private const val API_S = 31
    private const val API_TIRAMISU = 33
    private const val API_UPSIDE_DOWN_CAKE = 34

    @Suppress("LongParameterList")
    fun items(
        sdk: Int,
        packageName: String,
        inputs: CapabilityInputs,
        resolution: DeliveryResolution,
        unusedAppExempt: Boolean?,
        notificationsRequested: Boolean,
    ): List<FlowItem> {
        val pkg = Uri.parse("package:$packageName")
        return listOf(
            FlowItem(
                PermissionFlow.NOTIFICATIONS,
                needed = !inputs.notificationsEnabled || !inputs.criticalChannelAllowed,
                launch = notifications(sdk, packageName, notificationsRequested),
            ),
            FlowItem(
                PermissionFlow.EXACT_ALARM,
                needed = !inputs.exactAlarm,
                launch =
                    if (sdk in API_S until API_TIRAMISU) {
                        FlowLaunch.OpenSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                    } else {
                        null
                    },
            ),
            FlowItem(
                PermissionFlow.FULL_SCREEN_INTENT,
                needed = !inputs.fullScreenIntent,
                launch =
                    if (sdk >= API_UPSIDE_DOWN_CAKE) {
                        FlowLaunch.OpenSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                    } else {
                        null
                    },
            ),
            FlowItem(
                PermissionFlow.BATTERY,
                needed = !inputs.batteryExempt,
                launch = FlowLaunch.OpenSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            ),
            FlowItem(
                PermissionFlow.OVERLAY,
                // Offered only when an effective full screen intent is not available.
                needed = !inputs.overlayAllowed && !resolution.fullScreenIntent,
                launch = FlowLaunch.OpenSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)),
            ),
            FlowItem(
                PermissionFlow.UNUSED_APP_RESTRICTIONS,
                needed = unusedAppExempt == false,
                launch =
                    if (sdk >= API_R) {
                        FlowLaunch.OpenSettings(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkg))
                    } else {
                        null
                    },
            ),
        )
    }

    private fun notifications(
        sdk: Int,
        packageName: String,
        requested: Boolean,
    ): FlowLaunch =
        if (sdk >= API_TIRAMISU && !requested) {
            FlowLaunch.RuntimeRequest(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            FlowLaunch.OpenSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
}
