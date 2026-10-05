package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase
import com.momtime.shared.domain.DeliveryCapability
import kotlin.time.Instant

/** How a check ended: [PENDING] until it fires or times out, then [FIRED] or [MISSED]. */
enum class CheckOutcome { PENDING, FIRED, MISSED }

/**
 * One run of the check she starts (ADR 0069). [firedAt] is set exactly when [outcome] is [CheckOutcome.FIRED]. The tier
 * is the delivery tier the check was armed under, a platform capability difference that android keeps (ADR 0048).
 */
data class ReliabilityCheck(
    val id: Long,
    val scheduledAt: Instant,
    val firedAt: Instant?,
    val outcome: CheckOutcome,
    val resolvedTier: DeliveryCapability,
)

/**
 * The history of the check. No call throws in production (ADR 0054): a failure is counted and returns false, null or
 * an empty list. At most one check is pending at a time, which the schema enforces with a partial unique index.
 */
interface ReliabilityCheckRepository {
    /** The id of the new pending check, or null if one is already pending or the store failed. */
    fun startPending(
        scheduledAt: Instant,
        tier: DeliveryCapability,
    ): Long?

    fun pending(): ReliabilityCheck?

    /** True only if this call settled a pending check, so a check is settled once whoever races to it. */
    fun markFired(
        id: Long,
        firedAt: Instant,
    ): Boolean

    /** True only if this call settled a pending check. */
    fun markMissed(id: Long): Boolean

    /** Records the tier the check was really armed under, when arming fell back from the one resolved. */
    fun retier(
        id: Long,
        tier: DeliveryCapability,
    ): Boolean

    /**
     * Removes a pending check whose alarm could not be armed: it never ran, so it is no part of the history. True if
     * it was removed.
     */
    fun abandon(id: Long): Boolean

    /** Newest first. */
    fun recent(limit: Int): List<ReliabilityCheck>
}

class SqlDelightReliabilityCheckRepository(
    private val database: () -> AndroidStoreDatabase,
    private val failures: StoreFailures,
) : ReliabilityCheckRepository {
    override fun startPending(
        scheduledAt: Instant,
        tier: DeliveryCapability,
    ): Long? =
        nonFatal(failures, "reliability_check.start", null) {
            val queries = database().reliabilityCheckQueries
            queries.transactionWithResult {
                if (queries.selectPendingCheck().executeAsOneOrNull() != null) {
                    null
                } else {
                    queries.insertPendingCheck(scheduledAt.toEpochMilliseconds(), tier.name)
                    queries.lastInsertedCheckId().executeAsOne()
                }
            }
        }

    override fun pending(): ReliabilityCheck? =
        nonFatal(failures, "reliability_check.pending", null) {
            database().reliabilityCheckQueries.selectPendingCheck().executeAsOneOrNull()?.let {
                ReliabilityCheck(
                    it.id,
                    Instant.fromEpochMilliseconds(it.scheduled_at),
                    it.fired_at?.let(Instant::fromEpochMilliseconds),
                    CheckOutcome.valueOf(it.outcome),
                    DeliveryCapability.valueOf(it.resolved_tier),
                )
            }
        }

    override fun markFired(
        id: Long,
        firedAt: Instant,
    ): Boolean =
        nonFatal(failures, "reliability_check.fired", false) {
            val queries = database().reliabilityCheckQueries
            queries.transactionWithResult {
                queries.markCheckFired(firedAt.toEpochMilliseconds(), id)
                queries.changedRows().executeAsOne() > 0L
            }
        }

    override fun markMissed(id: Long): Boolean =
        nonFatal(failures, "reliability_check.missed", false) {
            val queries = database().reliabilityCheckQueries
            queries.transactionWithResult {
                queries.markCheckMissed(id)
                queries.changedRows().executeAsOne() > 0L
            }
        }

    override fun retier(
        id: Long,
        tier: DeliveryCapability,
    ): Boolean =
        nonFatal(failures, "reliability_check.retier", false) {
            val queries = database().reliabilityCheckQueries
            queries.transactionWithResult {
                queries.retierPendingCheck(tier.name, id)
                queries.changedRows().executeAsOne() > 0L
            }
        }

    override fun abandon(id: Long): Boolean =
        nonFatal(failures, "reliability_check.abandon", false) {
            val queries = database().reliabilityCheckQueries
            queries.transactionWithResult {
                queries.abandonPendingCheck(id)
                queries.changedRows().executeAsOne() > 0L
            }
        }

    override fun recent(limit: Int): List<ReliabilityCheck> =
        nonFatal(failures, "reliability_check.recent", emptyList()) {
            database().reliabilityCheckQueries.selectRecentChecks(limit.toLong()).executeAsList().map {
                ReliabilityCheck(
                    it.id,
                    Instant.fromEpochMilliseconds(it.scheduled_at),
                    it.fired_at?.let(Instant::fromEpochMilliseconds),
                    CheckOutcome.valueOf(it.outcome),
                    DeliveryCapability.valueOf(it.resolved_tier),
                )
            }
        }
}
