package com.momtime.android.capability

import com.momtime.shared.domain.DeliveryCapability

/**
 * What the platform lets this app do, read separately from what is decided about it (ADR 0050). The
 * resolution below is a pure function of these six inputs and nothing else, so every combination can be
 * written out and checked.
 *
 * - [exactAlarm]: `canScheduleExactAlarms()` on API 31 and above, and true below. A battery optimisation
 *   exemption also grants it, so it is never a permission check.
 * - [fullScreenIntent]: `canUseFullScreenIntent()` on API 34 and above, and below that whether
 *   `USE_FULL_SCREEN_INTENT` is declared.
 * - [notificationsEnabled]: notifications are allowed for the app at all.
 * - [batteryExempt]: the app is exempt from battery optimisation.
 * - [overlayAllowed]: the overlay (`SYSTEM_ALERT_WINDOW`) permission.
 * - [criticalChannelAllowed]: the Critical notification channel is not blocked. A user can block one channel
 *   while notifications stay enabled overall, and critical delivery must then not count as full screen.
 *   It is read from the Critical channel's importance (ADR 0060); a channel that does not exist yet is not blocked.
 */
data class CapabilityInputs(
    val exactAlarm: Boolean,
    val fullScreenIntent: Boolean,
    val notificationsEnabled: Boolean,
    val batteryExempt: Boolean,
    val overlayAllowed: Boolean,
    val criticalChannelAllowed: Boolean,
)

/** How the next alarm is armed. `setAlarmClock` is the only exact mechanism (ADR 0050). */
enum class DeliveryMechanism { SET_ALARM_CLOCK, SET_AND_ALLOW_WHILE_IDLE }

/** Android's own tier, mapped to the shared [DeliveryCapability] by [toDeliveryCapability]. */
enum class ResolvedTier { FULL, EXACT, INEXACT }

fun ResolvedTier.toDeliveryCapability(): DeliveryCapability =
    when (this) {
        ResolvedTier.FULL -> DeliveryCapability.TIER_3
        ResolvedTier.EXACT -> DeliveryCapability.TIER_2
        ResolvedTier.INEXACT -> DeliveryCapability.TIER_1
    }

/**
 * The resolution: the tier, the mechanism that arms the alarm, and the presentation flags.
 *
 * - [fullScreenIntent]: the ring screen can be launched over the lock screen.
 * - [headsUp]: a heads up notification is shown alongside the ringer service.
 * - [overlayAvailable]: the overlay route to starting the ring screen is open.
 * - [audioOnly]: the ringer sounds and nothing is visible.
 * - [undeliverable]: no exact capability and no notification delivered, so nothing reaches her; the app
 *   shows a blocking banner and gates onboarding.
 */
data class DeliveryResolution(
    val tier: ResolvedTier,
    val mechanism: DeliveryMechanism,
    val fullScreenIntent: Boolean,
    val headsUp: Boolean,
    val overlayAvailable: Boolean,
    val audioOnly: Boolean,
    val undeliverable: Boolean,
) {
    val capability: DeliveryCapability get() = tier.toDeliveryCapability()
}

/**
 * Tier 3: exact, plus effective full screen intent, plus battery exempt. Tier 2: exact, short of Tier 3.
 * Tier 1: not exact. Effective full screen intent is the platform's, and notifications enabled, and the
 * Critical channel not blocked (ADR 0050).
 */
fun resolveDelivery(inputs: CapabilityInputs): DeliveryResolution {
    val notificationsDelivered = inputs.notificationsEnabled && inputs.criticalChannelAllowed
    val effectiveFullScreenIntent = inputs.fullScreenIntent && notificationsDelivered
    return if (inputs.exactAlarm) {
        exactResolution(inputs, notificationsDelivered, effectiveFullScreenIntent)
    } else {
        // No exact capability: an inexact alarm and plain notifications. Nothing else is available, and
        // with no notification delivered either, nothing reaches her.
        DeliveryResolution(
            tier = ResolvedTier.INEXACT,
            mechanism = DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE,
            fullScreenIntent = false,
            headsUp = false,
            overlayAvailable = false,
            audioOnly = false,
            undeliverable = !notificationsDelivered,
        )
    }
}

private fun exactResolution(
    inputs: CapabilityInputs,
    notificationsDelivered: Boolean,
    effectiveFullScreenIntent: Boolean,
): DeliveryResolution {
    val tier =
        if (effectiveFullScreenIntent && inputs.batteryExempt) ResolvedTier.FULL else ResolvedTier.EXACT
    val headsUp = notificationsDelivered
    val overlayAvailable = inputs.overlayAllowed
    return DeliveryResolution(
        tier = tier,
        mechanism = DeliveryMechanism.SET_ALARM_CLOCK,
        fullScreenIntent = effectiveFullScreenIntent,
        headsUp = headsUp,
        overlayAvailable = overlayAvailable,
        audioOnly = !effectiveFullScreenIntent && !headsUp && !overlayAvailable,
        undeliverable = false,
    )
}
