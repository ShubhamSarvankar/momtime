package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase
import kotlin.time.Instant

/**
 * How many times the clock has been set (ADR 0070). Only the count is read: a rung armed under one count and fired
 * under another had the clock moved under it, and its latency says nothing about delivery.
 */
interface ClockChangeRepository {
    /** True if the change was stored. */
    fun record(at: Instant): Boolean

    /** The count, or null if the store cannot be read: a fire is then not vouched for. */
    fun count(): Long?
}

class SqlDelightClockChangeRepository(
    private val database: () -> AndroidStoreDatabase,
    private val failures: StoreFailures,
) : ClockChangeRepository {
    override fun record(at: Instant): Boolean =
        nonFatal(failures, "clock_change.record", false) {
            database().clockChangeQueries.insertClockChange(at.toEpochMilliseconds())
            true
        }

    override fun count(): Long? =
        nonFatal(failures, "clock_change.count", null) {
            database().clockChangeQueries.countClockChanges().executeAsOne()
        }
}
