package com.momtime.android.store

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The production policy for a store failure (ADR 0054): count it per operation, log it, and let the call
 * return its fallback. [counts] is what the reliability view reads (PR 7), so a store that fails every
 * time is visible there and is not a silent repair loop.
 *
 * The count is persisted (ADR 0070). Each failure is written through to a [StoreFailureRepository] once one is given
 * with [persistThrough], so the count survives the process that saw the failures: a count held only in memory is gone
 * exactly when a store that fails every time most needs to stay visible. A failure that could not be written (the store
 * itself is what is failing) is kept in memory until it is, so [counts] is the persisted counts plus what has not yet
 * been persisted, and a second instance over the same store reads what the first persisted.
 *
 * The log line names the operation and the exception's class and nothing else. An exception message from the
 * database layer can carry SQL text or values, and CLAUDE.md invariant 11 allows no PII in a log.
 */
class CountingStoreFailures(
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : StoreFailures {
    private val unpersisted = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var repository: (() -> StoreFailureRepository)? = null

    /** From now on every failure is written to the repository [provider] gives, which is asked on each failure. */
    fun persistThrough(provider: () -> StoreFailureRepository) {
        repository = provider
    }

    /** Failures so far, by operation: the persisted counts and any that could not be persisted yet. */
    fun counts(): Map<String, Long> {
        val persisted = repository?.invoke()?.counts().orEmpty()
        val merged = persisted.toMutableMap()
        unpersisted.forEach { (operation, count) -> merged[operation] = (merged[operation] ?: 0L) + count.get() }
        return merged.filterValues { it > 0L }
    }

    val total: Long get() = counts().values.sum()

    override fun onFailure(
        operation: String,
        cause: RuntimeException,
    ) {
        val stored = runCatching { repository?.invoke()?.increment(operation) }.getOrNull() == true
        if (!stored) unpersisted.computeIfAbsent(operation) { AtomicLong() }.incrementAndGet()
        log("store failure in $operation: ${cause.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "MomTimeStore"
    }
}

/**
 * The policy of the repository that holds the failure counts (ADR 0070): it logs and does nothing else, so that a store
 * that cannot count a failure does not count that failure back into itself.
 */
class LogOnlyStoreFailures(
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : StoreFailures {
    override fun onFailure(
        operation: String,
        cause: RuntimeException,
    ) = log("store failure in $operation: ${cause.javaClass.simpleName}")

    private companion object {
        const val TAG = "MomTimeStore"
    }
}
