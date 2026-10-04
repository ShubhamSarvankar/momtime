package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.EscalationRung

/**
 * Resumes a ladder after a reboot or force-stop (golden scenarios 2, 18): the remaining rungs
 * are exactly the ladder minus however many have already fired, never the full original
 * ladder. Also resolves which single rung to arm next across every occurrence at once (golden
 * scenario 15) — the architecture's "one alarm at a time" invariant (ADR 0017) means the
 * platform only ever asks "what is the chronologically next rung, across everything," never
 * "what is the next rung for this specific occurrence" in isolation.
 *
 * Both functions take the set of channels the caller delivers (invariant 6: the engine does not know
 * what a platform does with a rung, so the caller says which ones it delivers). A rung on any other
 * channel does not exist for that caller: it is never selected, and it is not counted among the rungs
 * that have fired. The count and the filter use the same set, so a fire count recorded by a caller that
 * delivers RING and RING_REPEAT is read against a ladder of exactly those.
 */
object NextRungResolver {
    /**
     * [firedCount] rungs of [channels], in ladder order, are already fired — assumes rungs fire strictly
     * in order. The filter runs first, so a rung of another channel never shifts the count.
     */
    fun remaining(
        ladder: List<EscalationRung>,
        firedCount: Int,
        channels: Set<Channel>,
    ): List<EscalationRung> = ladder.filter { it.channel in channels }.drop(firedCount)

    data class PendingLadder(
        val occurrenceId: String,
        val remainingRungs: List<EscalationRung>,
    )

    /**
     * The single next rung of [channels] to arm across all occurrences, or null if nothing is pending.
     * Chronologically earliest wins regardless of which occurrence it belongs to. Two rungs at the same
     * instant are ordered by occurrence id, then by their position in the ladder, so the result never
     * depends on the order [pendingLadders] was built in. When the first of them has fired the second is
     * the next one, at an instant that has already passed, and the caller arms it at once.
     */
    fun globalNext(
        pendingLadders: List<PendingLadder>,
        channels: Set<Channel>,
    ): Pair<String, EscalationRung>? =
        pendingLadders
            .sortedBy { it.occurrenceId }
            .flatMap { pending ->
                pending.remainingRungs.filter { it.channel in channels }.map { pending.occurrenceId to it }
            }.minByOrNull { (_, rung) -> rung.instant }
}
