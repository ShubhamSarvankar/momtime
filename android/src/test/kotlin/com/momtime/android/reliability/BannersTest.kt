package com.momtime.android.reliability

import com.momtime.android.capability.CapabilityInputs
import com.momtime.android.capability.ResolvedTier
import com.momtime.android.capability.resolveDelivery
import com.momtime.android.store.CheckOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The banner state (ADR 0070): every threshold is tested at its boundary and one step either side, because a threshold
 * that is off by one is the failure a constant hides. The thresholds are Claude (technical review)'s decision, to be
 * tuned from the A15 soak; the banner itself is Phase 3.
 */
class BannersTest {
    private val tier3 =
        CapabilityInputs(
            exactAlarm = true,
            fullScreenIntent = true,
            notificationsEnabled = true,
            batteryExempt = true,
            overlayAllowed = false,
            criticalChannelAllowed = true,
        )

    @Suppress("LongParameterList")
    private fun inputs(
        unseenBoots: Int = 0,
        samsung: Boolean = false,
        unusedAppExempt: Boolean? = null,
        latencies: List<Duration> = emptyList(),
        neverFired: Int = 0,
        lastCheck: CheckOutcome? = null,
        capability: CapabilityInputs = tier3,
        muted: Int = 0,
        failures: Long = 0,
        corruption: Boolean = false,
    ) = BannerInputs(
        latencies,
        neverFired,
        lastCheck,
        capability,
        muted,
        failures,
        corruption,
        unseenBoots,
        samsung,
        unusedAppExempt,
    )

    private fun banners(vararg changes: BannerInputs) = Banners.compute(changes.single())

    @Test
    fun `a healthy phone raises nothing`() {
        assertEquals(
            emptyList<Banner>(),
            banners(inputs(latencies = listOf(3.seconds, 4.seconds), lastCheck = CheckOutcome.FIRED)),
        )
        assertEquals("no data is no banner either", emptyList<Banner>(), banners(inputs()))
    }

    @Test
    fun `the median of exact tier fires raises a banner only above two minutes`() {
        val limit = Banners.MEDIAN_LATENCY_LIMIT
        assertEquals(2.minutes, limit)
        assertEquals("at the limit, none", emptyList<Banner>(), banners(inputs(latencies = listOf(limit))))
        assertEquals(
            "one millisecond above, one",
            listOf<Banner>(Banner.SlowMedian(limit + 1.milliseconds)),
            banners(inputs(latencies = listOf(limit + 1.milliseconds))),
        )
        assertEquals(
            "one millisecond below, none",
            emptyList<Banner>(),
            banners(inputs(latencies = listOf(limit - 1.milliseconds))),
        )
    }

    @Test
    fun `the median is the middle fire, and the mean of the two middles when there is an even number`() {
        val sorted = listOf(1.seconds, 3.minutes, 4.minutes)
        assertEquals(3.minutes, Banners.median(sorted))
        assertEquals(
            "four fires: the mean of the second and third",
            2.minutes + 30.seconds,
            Banners.median(listOf(1.seconds, 2.minutes, 3.minutes, 4.minutes)),
        )
        assertEquals(
            "so two fast fires and two slow ones sit on the boundary's mean",
            listOf<Banner>(Banner.SlowMedian(2.minutes + 30.seconds)),
            banners(inputs(latencies = listOf(4.minutes, 1.seconds, 3.minutes, 2.minutes))),
        )
    }

    @Test
    fun `a single exact tier fire raises a banner only above five minutes`() {
        val limit = Banners.MAX_LATENCY_LIMIT
        assertEquals(5.minutes, limit)
        assertEquals(
            "at the limit, none",
            emptyList<Banner>(),
            banners(inputs(latencies = listOf(1.seconds, 1.seconds, 1.seconds, limit))),
        )
        assertEquals(
            "one millisecond above, one, with the others fast",
            listOf<Banner>(Banner.SlowFire(limit + 1.milliseconds)),
            banners(inputs(latencies = listOf(1.seconds, 1.seconds, 1.seconds, limit + 1.milliseconds))),
        )
        assertEquals(
            "one millisecond below, none",
            emptyList<Banner>(),
            banners(inputs(latencies = listOf(1.seconds, 1.seconds, 1.seconds, limit - 1.milliseconds))),
        )
    }

    @Test
    fun `a median above the limit and a slowest above it are two banners`() {
        val result = banners(inputs(latencies = listOf(6.minutes, 6.minutes, 6.minutes)))
        assertEquals(listOf<Banner>(Banner.SlowMedian(6.minutes), Banner.SlowFire(6.minutes)), result)
    }

    @Test
    fun `a rung armed ahead that never fired raises a banner from the first`() {
        assertEquals(1, Banners.NEVER_FIRED_LIMIT)
        assertEquals(emptyList<Banner>(), banners(inputs(neverFired = 0)))
        assertEquals(listOf<Banner>(Banner.NeverFired(1)), banners(inputs(neverFired = 1)))
        assertEquals(listOf<Banner>(Banner.NeverFired(2)), banners(inputs(neverFired = 2)))
    }

    @Test
    fun `a failed check raises a banner, a passed one or none does not, and a running one is not a result`() {
        assertEquals(listOf<Banner>(Banner.CheckFailed), banners(inputs(lastCheck = CheckOutcome.MISSED)))
        assertEquals(emptyList<Banner>(), banners(inputs(lastCheck = CheckOutcome.FIRED)))
        assertEquals(emptyList<Banner>(), banners(inputs(lastCheck = null)))
    }

    @Test
    fun `a muted alarm stream raises a banner from the first fire`() {
        assertEquals(1, Banners.MUTED_LIMIT)
        assertEquals(emptyList<Banner>(), banners(inputs(muted = 0)))
        assertEquals(listOf<Banner>(Banner.MutedAlarmStream(1)), banners(inputs(muted = 1)))
    }

    @Test
    fun `a store failure or a corruption marker raises a banner`() {
        assertEquals(1L, Banners.STORE_FAILURE_LIMIT)
        assertEquals(emptyList<Banner>(), banners(inputs(failures = 0)))
        assertEquals(listOf<Banner>(Banner.StoreTrouble(1, false)), banners(inputs(failures = 1)))
        assertEquals(listOf<Banner>(Banner.StoreTrouble(0, true)), banners(inputs(corruption = true)))
        assertEquals(listOf<Banner>(Banner.StoreTrouble(3, true)), banners(inputs(failures = 3, corruption = true)))
    }

    @Test
    fun `a tier below three raises a banner that names each input that is missing`() {
        fun missing(capability: CapabilityInputs) =
            (banners(inputs(capability = capability)).single() as Banner.BelowTier3).missing

        assertEquals(listOf(MissingInput.EXACT_ALARM), missing(tier3.copy(exactAlarm = false)))
        assertEquals(listOf(MissingInput.NOTIFICATIONS), missing(tier3.copy(notificationsEnabled = false)))
        assertEquals(listOf(MissingInput.CRITICAL_CHANNEL), missing(tier3.copy(criticalChannelAllowed = false)))
        assertEquals(listOf(MissingInput.FULL_SCREEN_INTENT), missing(tier3.copy(fullScreenIntent = false)))
        assertEquals(listOf(MissingInput.BATTERY_EXEMPTION), missing(tier3.copy(batteryExempt = false)))
        assertEquals(
            listOf(MissingInput.EXACT_ALARM, MissingInput.FULL_SCREEN_INTENT, MissingInput.BATTERY_EXEMPTION),
            missing(tier3.copy(exactAlarm = false, fullScreenIntent = false, batteryExempt = false)),
        )
    }

    @Test
    fun `the overlay is not part of tier three, so its absence raises nothing`() {
        assertEquals(emptyList<Banner>(), banners(inputs(capability = tier3.copy(overlayAllowed = false))))
        assertEquals(emptyList<Banner>(), banners(inputs(capability = tier3.copy(overlayAllowed = true))))
    }

    // The banner follows the resolution, and the list of what is missing follows the banner, for every combination of
    // the six inputs the platform can give.
    @Test
    fun `for every combination of inputs the banner is raised exactly when the resolved tier is below three`() {
        var combinations = 0
        for (bits in 0 until 64) {
            val inputs =
                CapabilityInputs(
                    exactAlarm = bits and 1 != 0,
                    fullScreenIntent = bits and 2 != 0,
                    notificationsEnabled = bits and 4 != 0,
                    batteryExempt = bits and 8 != 0,
                    overlayAllowed = bits and 16 != 0,
                    criticalChannelAllowed = bits and 32 != 0,
                )
            val raised = banners(inputs(capability = inputs)).filterIsInstance<Banner.BelowTier3>()
            val below = resolveDelivery(inputs).tier != ResolvedTier.FULL
            assertEquals("bits=$bits", below, raised.isNotEmpty())
            assertEquals(
                "bits=$bits: what is missing is non empty exactly when the tier is below three",
                below,
                Banners.missingForTier3(inputs).isNotEmpty(),
            )
            combinations++
        }
        assertEquals(64, combinations)
    }

    @Test
    fun `only exact tiers are held to the latency limits`() {
        assertTrue(Banners.isExactTier(com.momtime.shared.domain.DeliveryCapability.TIER_3))
        assertTrue(Banners.isExactTier(com.momtime.shared.domain.DeliveryCapability.TIER_2))
        assertEquals(false, Banners.isExactTier(com.momtime.shared.domain.DeliveryCapability.TIER_1))
    }

    @Test
    fun `a restart the app did not run after raises a banner from the first, with the fix path for the phone`() {
        assertEquals(1, Banners.UNSEEN_BOOT_LIMIT)
        assertEquals(emptyList<Banner>(), banners(inputs(unseenBoots = 0)))
        assertEquals(
            listOf<Banner>(Banner.NotRunAfterRestart(1, FixStep.BATTERY_STEP)),
            banners(inputs(unseenBoots = 1)),
        )
        assertEquals(
            "on a Samsung it names the Sleeping apps and Deep sleeping apps steps",
            listOf<Banner>(Banner.NotRunAfterRestart(2, FixStep.SAMSUNG_SLEEPING_STEPS)),
            banners(inputs(unseenBoots = 2, samsung = true)),
        )
    }

    @Test
    fun `unused app restrictions raise a banner only when they apply`() {
        assertEquals(
            listOf<Banner>(Banner.UnusedAppRestrictions(FixStep.UNUSED_APP_STEP)),
            banners(inputs(unusedAppExempt = false)),
        )
        assertEquals("exempt: none", emptyList<Banner>(), banners(inputs(unusedAppExempt = true)))
        assertEquals(
            "below API 30 the setting does not exist: none",
            emptyList<Banner>(),
            banners(inputs(unusedAppExempt = null)),
        )
    }
}
