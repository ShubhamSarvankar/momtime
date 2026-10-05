package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase
import kotlin.time.Instant

/** When one boot began, by its boot count. */
data class BootInstant(
    val bootCount: Long,
    val bootedAt: Instant,
)

/**
 * The instants the device's boots began (ADR 0070). No call throws in production (ADR 0054): a failed write is false
 * and a failed read is an empty list, which means no boot is known, and a rung that never fired is then not excused.
 */
interface BootInstantRepository {
    /** Records the boot once: the first answer for a boot count is kept. True if the call stored it. */
    fun record(boot: BootInstant): Boolean

    /** Every boot the app has seen, earliest first. */
    fun all(): List<BootInstant>
}

class SqlDelightBootInstantRepository(
    private val database: () -> AndroidStoreDatabase,
    private val failures: StoreFailures,
) : BootInstantRepository {
    override fun record(boot: BootInstant): Boolean =
        nonFatal(failures, "boot_instant.record", false) {
            database().bootInstantQueries.insertBootInstant(boot.bootCount, boot.bootedAt.toEpochMilliseconds())
            true
        }

    override fun all(): List<BootInstant> =
        nonFatal(failures, "boot_instant.all", emptyList()) {
            database().bootInstantQueries.selectBootInstants().executeAsList().map {
                BootInstant(it.boot_count, Instant.fromEpochMilliseconds(it.booted_at))
            }
        }
}
