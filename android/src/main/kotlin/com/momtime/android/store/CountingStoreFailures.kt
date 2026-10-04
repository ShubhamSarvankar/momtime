package com.momtime.android.store

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The production policy for a store failure (ADR 0054): count it per operation, log it, and let the call
 * return its fallback. [counts] is what the reliability view reads (PR 7), so a store that fails every time
 * is visible there and is not a silent repair loop.
 *
 * The log line names the operation and the exception's class and nothing else. An exception message from the
 * database layer can carry SQL text or values, and CLAUDE.md invariant 11 allows no PII in a log.
 */
class CountingStoreFailures(
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : StoreFailures {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    /** Failures so far, by operation. */
    fun counts(): Map<String, Long> = counters.mapValues { it.value.get() }

    val total: Long get() = counters.values.sumOf { it.get() }

    override fun onFailure(
        operation: String,
        cause: RuntimeException,
    ) {
        counters.computeIfAbsent(operation) { AtomicLong() }.incrementAndGet()
        log("store failure in $operation: ${cause.javaClass.simpleName}")
    }

    private companion object {
        const val TAG = "MomTimeStore"
    }
}
