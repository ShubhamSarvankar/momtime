package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase

/**
 * The persisted count of store failures by operation (ADR 0070). It is what [CountingStoreFailures] writes through, so
 * the count survives the process that saw the failures. It must never fail the call that is already failing: its own
 * errors are logged and answered with false or an empty map, and never counted back into itself.
 */
interface StoreFailureRepository {
    /** True if the count was stored. */
    fun increment(operation: String): Boolean

    /** Failures so far by operation, or an empty map if the store cannot be read. */
    fun counts(): Map<String, Long>
}

class SqlDelightStoreFailureRepository(
    private val database: () -> AndroidStoreDatabase,
    private val failures: StoreFailures,
) : StoreFailureRepository {
    override fun increment(operation: String): Boolean =
        nonFatal(failures, "store_failure.increment", false) {
            val queries = database().storeFailureQueries
            queries.transaction {
                queries.ensureFailureRow(operation)
                queries.bumpFailure(operation)
            }
            true
        }

    override fun counts(): Map<String, Long> =
        nonFatal(failures, "store_failure.counts", emptyMap()) {
            database().storeFailureQueries.selectFailureCounts().executeAsList().associate {
                it.operation to it.failures
            }
        }
}
