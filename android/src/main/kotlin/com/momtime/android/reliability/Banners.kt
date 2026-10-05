package com.momtime.android.reliability

import com.momtime.android.capability.CapabilityInputs
import com.momtime.android.capability.ResolvedTier
import com.momtime.android.capability.resolveDelivery
import com.momtime.android.store.CheckOutcome
import com.momtime.shared.domain.DeliveryCapability
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What the reliability banner should say, as data (ADR 0070). PR 7 computes the state and tests every boundary; the
 * banner itself, and the fix path it opens, are Phase 3 and PR 8. The thresholds are constants to be tuned from the
 * A15 soak (`MANUAL_CHECKS.md` item 5), not argued now: they are Claude (technical review)'s decision.
 */
sealed interface Banner {
    /** The median latency of recent exact tier fires is above [Banners.MEDIAN_LATENCY_LIMIT]. */
    data class SlowMedian(
        val median: Duration,
    ) : Banner

    /** An exact tier fire was later than [Banners.MAX_LATENCY_LIMIT]. */
    data class SlowFire(
        val slowest: Duration,
    ) : Banner

    /** A rung that was armed ahead of time never fired: the headline signal. */
    data class NeverFired(
        val count: Int,
    ) : Banner

    /** The last check she ran did not fire in time. */
    data object CheckFailed : Banner

    /** The delivery tier is below 3; [missing] names what is missing, so the fix path can name it. */
    data class BelowTier3(
        val missing: List<MissingInput>,
    ) : Banner

    /** The alarm stream was muted when an alarm fired: a ring she could not hear. */
    data class MutedAlarmStream(
        val fires: Int,
    ) : Banner

    /** A store operation has failed (ADR 0054), or the database was found corrupt and replaced (ADR 0044). */
    data class StoreTrouble(
        val failures: Long,
        val corruption: Boolean,
    ) : Banner
}

/** An input whose absence keeps the device below Tier 3, in the order the fix path should take them. */
enum class MissingInput { EXACT_ALARM, NOTIFICATIONS, CRITICAL_CHANNEL, FULL_SCREEN_INTENT, BATTERY_EXEMPTION }

/** What the banner state is computed from: the report's figures, already reduced, and the capability now. */
data class BannerInputs(
    /** Latencies of counted fires on an exact tier (Tier 2 and 3). A Tier 1 fire may legitimately be minutes late. */
    val exactTierLatencies: List<Duration>,
    val neverFired: Int,
    /** The outcome of the last check that settled, or null if none has. A check still running is not one. */
    val lastCheck: CheckOutcome?,
    val capability: CapabilityInputs,
    val mutedFires: Int,
    val storeFailures: Long,
    val corruption: Boolean,
)

object Banners {
    /** A median above this, over recent exact tier fires, raises [Banner.SlowMedian]. The boundary itself does not. */
    val MEDIAN_LATENCY_LIMIT: Duration = 2.minutes

    /** Any exact tier fire later than this raises [Banner.SlowFire]. The boundary itself does not. */
    val MAX_LATENCY_LIMIT: Duration = 5.minutes

    /** This many never fired rungs, or more, raise [Banner.NeverFired]. */
    const val NEVER_FIRED_LIMIT = 1

    /** This many store failures, or more, raise [Banner.StoreTrouble]. */
    const val STORE_FAILURE_LIMIT = 1L

    /** This many fires on a muted alarm stream, or more, raise [Banner.MutedAlarmStream]. */
    const val MUTED_LIMIT = 1

    fun compute(inputs: BannerInputs): List<Banner> =
        buildList {
            val latencies = inputs.exactTierLatencies.sorted()
            if (latencies.isNotEmpty()) {
                val median = median(latencies)
                if (median > MEDIAN_LATENCY_LIMIT) add(Banner.SlowMedian(median))
                val slowest = latencies.last()
                if (slowest > MAX_LATENCY_LIMIT) add(Banner.SlowFire(slowest))
            }
            if (inputs.neverFired >= NEVER_FIRED_LIMIT) add(Banner.NeverFired(inputs.neverFired))
            if (inputs.lastCheck == CheckOutcome.MISSED) add(Banner.CheckFailed)
            val missing = missingForTier3(inputs.capability)
            if (resolveDelivery(inputs.capability).tier != ResolvedTier.FULL) add(Banner.BelowTier3(missing))
            if (inputs.mutedFires >= MUTED_LIMIT) add(Banner.MutedAlarmStream(inputs.mutedFires))
            if (inputs.storeFailures >= STORE_FAILURE_LIMIT || inputs.corruption) {
                add(Banner.StoreTrouble(inputs.storeFailures, inputs.corruption))
            }
        }

    /** The inputs that, missing, keep the device from Tier 3 (ADR 0050): exact, notifications, full screen, battery. */
    fun missingForTier3(inputs: CapabilityInputs): List<MissingInput> =
        buildList {
            if (!inputs.exactAlarm) add(MissingInput.EXACT_ALARM)
            if (!inputs.notificationsEnabled) add(MissingInput.NOTIFICATIONS)
            if (!inputs.criticalChannelAllowed) add(MissingInput.CRITICAL_CHANNEL)
            if (!inputs.fullScreenIntent) add(MissingInput.FULL_SCREEN_INTENT)
            if (!inputs.batteryExempt) add(MissingInput.BATTERY_EXEMPTION)
        }

    /** The middle of a sorted list; of two middles, their mean. */
    fun median(sorted: List<Duration>): Duration {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** True for the tiers whose alarms are exact (`setAlarmClock`): 2 and 3. */
    fun isExactTier(tier: DeliveryCapability): Boolean = tier != DeliveryCapability.TIER_1
}
