package com.momtime.android.delivery

import com.momtime.android.arming.FiredRung
import com.momtime.android.arming.Presentation
import com.momtime.android.capability.DeliveryResolution
import com.momtime.android.capability.ResolvedTier
import com.momtime.shared.engine.RungDelivery

/**
 * The six ways a fired rung reaches her (ADR 0060), one path each. The path is a function of the rung's
 * presentation, what the domain decided, and the capability resolution, and of nothing else: no criticality is
 * read here. A Gentle occurrence takes the plain path on any tier because the domain decided `NOTIFICATION` for
 * it (ADR 0089), not because this code looked at its criticality.
 */
internal enum class DeliveryPath {
    /** Tier 3: a full screen intent notification and the ringer service. */
    RING,

    /** Tier 2, exact but without an effective full screen intent: a heads up notification and the ringer. */
    HEADS_UP,

    /** Notifications denied: the ringer sounds and nothing else is shown, except through the overlay route. */
    AUDIO_ONLY,

    /** Tier 1, no exact capability, or a Gentle occurrence on any tier: a plain notification, and no ringer. */
    PLAIN,

    /** A rung beyond the catch up window: a silent notification, no ring and no heads up (ADR 0056). */
    SILENT_NOTICE,

    /** Quiet hours or the interruption budget, decided by the domain: a silent notification. */
    SILENT,
    ;

    /** True if this path starts, or continues, a ring session: the ringer is behind it. */
    val rings: Boolean get() = this == RING || this == HEADS_UP || this == AUDIO_ONLY

    companion object {
        /**
         * A rung that continues a ring already in progress keeps ringing whatever its own presentation would
         * have been, so only a rung that is not continuing can be one of the silent paths or the plain path the
         * domain chose. The domain's answer is tested before the capability: a `NOTIFICATION` rung is plain on
         * every tier.
         */
        fun choose(
            rung: FiredRung,
            resolution: DeliveryResolution,
        ): DeliveryPath =
            when {
                !rung.continuing && rung.presentation == Presentation.SILENT_NOTICE -> SILENT_NOTICE
                !rung.continuing && rung.policy == RungDelivery.SILENT_NOTIFICATION -> SILENT
                !rung.continuing && rung.policy == RungDelivery.NOTIFICATION -> PLAIN
                resolution.tier == ResolvedTier.INEXACT -> PLAIN
                resolution.fullScreenIntent -> RING
                resolution.headsUp -> HEADS_UP
                else -> AUDIO_ONLY
            }
    }
}
