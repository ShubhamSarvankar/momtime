package com.momtime.android.onboarding

import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.arming.CapabilityResolver
import com.momtime.android.di.DeviceManufacturer
import com.momtime.android.di.UnusedAppRestrictions
import com.momtime.android.settings.AndroidSettings
import com.momtime.shared.domain.DeliveryCapability
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** What the setup screen shows: each flow, the tier she is at now, and whether the phone is a Samsung. */
data class SetupState(
    val items: List<FlowItem>,
    val tier: DeliveryCapability,
    val samsung: Boolean,
)

/** What the setup screens need from the rest of the app, and nothing else. Set when the application starts. */
interface SetupHost {
    /**
     * She has come back from a settings screen or a permission dialog (or has just arrived). Capability is resolved
     * again, from the platform as it is now, and then `ensureArmed` is called, so a reminder that could not be armed
     * through the old tier is armed through the new one at once (ADR 0072). Returns what is still needed. It reads and
     * writes the database, so the screen calls it off the main thread.
     */
    fun onReturn(): SetupState

    /** The runtime request for notifications has been made: the next ask opens the settings, where a dialog cannot. */
    fun markNotificationsRequested()

    fun isSamsung(): Boolean
}

object SetupEntryPoint {
    @Volatile
    var provider: (() -> SetupHost)? = null

    /** Where the screens run what reads the database. A test replaces it with one that runs at once. */
    @Volatile
    var executor: Executor = Executors.newSingleThreadExecutor()
}

@Suppress("LongParameterList")
class SetupController internal constructor(
    private val packageName: String,
    private val sdk: Int,
    private val resolver: CapabilityResolver,
    private val coordinator: ArmingCoordinator,
    private val settings: AndroidSettings,
    private val unusedApp: UnusedAppRestrictions,
    private val manufacturer: DeviceManufacturer,
) : SetupHost {
    override fun onReturn(): SetupState {
        // Capability is resolved at runtime, from the platform as it is now, never remembered and never inferred.
        val resolution = resolver.resolve()
        val state =
            SetupState(
                PermissionFlows.items(
                    sdk,
                    packageName,
                    resolver.inputs,
                    resolution,
                    unusedApp.isExempt(),
                    settings.notificationsRequested(),
                ),
                resolution.capability,
                manufacturer.isSamsung(),
            )
        coordinator.ensureArmed()
        return state
    }

    override fun markNotificationsRequested() = settings.markNotificationsRequested()

    override fun isSamsung(): Boolean = manufacturer.isSamsung()
}
