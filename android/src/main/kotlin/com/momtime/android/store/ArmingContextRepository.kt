package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase
import kotlin.time.Instant

/**
 * What the device said when a rung was armed (ADR 0070): the rung's instant, the boot count and the count of clock
 * changes. It is keyed by the id of the shared `ALARM_SCHEDULED` event written for that arming.
 */
data class ArmingContext(
    val eventId: String,
    val rungInstant: Instant,
    val bootCount: Long,
    val clockChanges: Long,
)

/** No call throws in production (ADR 0054): a failed read is null, a failed write is false, and it is counted. */
interface ArmingContextRepository {
    fun record(context: ArmingContext): Boolean

    fun find(eventId: String): ArmingContext?
}

class SqlDelightArmingContextRepository(
    private val database: () -> AndroidStoreDatabase,
    private val failures: StoreFailures,
) : ArmingContextRepository {
    override fun record(context: ArmingContext): Boolean =
        nonFatal(failures, "arming_context.record", false) {
            database().armingContextQueries.insertArmingContext(
                context.eventId,
                context.rungInstant.toEpochMilliseconds(),
                context.bootCount,
                context.clockChanges,
            )
            true
        }

    override fun find(eventId: String): ArmingContext? =
        nonFatal(failures, "arming_context.find", null) {
            database().armingContextQueries.selectArmingContext(eventId).executeAsOneOrNull()?.let {
                ArmingContext(
                    it.event_id,
                    Instant.fromEpochMilliseconds(it.rung_instant),
                    it.boot_count,
                    it.clock_changes,
                )
            }
        }
}
