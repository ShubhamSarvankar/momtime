package com.momtime.shared.engine

import com.momtime.shared.domain.EscalationRung

/**
 * Resumes a ladder after a reboot or force-stop (golden scenarios 2, 18): the remaining rungs
 * are exactly the ladder minus however many have already fired, never the full original
 * ladder. Also resolves which single rung to arm next across every occurrence at once (golden
 * scenario 15) — the architecture's "one alarm at a time" invariant (ADR 0017) means the
 * platform only ever asks "what is the chronologically next rung, across everything," never
 * "what is the next rung for this specific occurrence" in isolation.
 */
object NextRungResolver {
    /** [firedCount] rungs, in ladder order, are already fired — assumes rungs fire strictly in order. */
    fun remaining(
        ladder: List<EscalationRung>,
        firedCount: Int,
    ): List<EscalationRung> = ladder.drop(firedCount)

    data class PendingLadder(
        val occurrenceId: String,
        val remainingRungs: List<EscalationRung>,
    )

    /**
     * The single next rung to arm across all occurrences, or null if nothing is pending.
     * Chronologically earliest wins regardless of which occurrence it belongs to.
     */
    fun globalNext(pendingLadders: List<PendingLadder>): Pair<String, EscalationRung>? =
        pendingLadders
            .flatMap { pending -> pending.remainingRungs.map { pending.occurrenceId to it } }
            .minByOrNull { (_, rung) -> rung.instant }
}
