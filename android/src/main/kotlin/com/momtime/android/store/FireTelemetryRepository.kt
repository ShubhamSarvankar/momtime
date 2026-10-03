package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase
import com.momtime.shared.domain.DeliveryCapability

/**
 * What the device did when an alarm fired, kept by android (ADR 0048). The shared event log holds the
 * platform neutral facts; this holds the delivery tier and the state of the device, keyed by the
 * shared event's id with no foreign key across the two files. Per fire timing is not stored here:
 * it is the rung's instant against `ALARM_FIRED`'s `deviceTimestamp`.
 */
data class FireTelemetry(
    val eventId: String,
    val resolvedTier: DeliveryCapability,
    val screenOn: Boolean?,
    val audioFocusObtained: Boolean?,
    val batteryPct: Int?,
    val dozeState: String?,
    val watchdogRepair: Boolean,
    val bootCount: Long?,
)

/**
 * No call throws (ADR 0051): a failure returns false, null or zero, because a lost telemetry row must
 * never stop an alarm.
 */
interface FireTelemetryRepository {
    /** True if the row was stored. */
    fun insert(telemetry: FireTelemetry): Boolean

    /** The row, or null if there is none or the store failed. */
    fun findForEvent(eventId: String): FireTelemetry?

    /** The number of rows, or zero if the store failed. */
    fun count(): Long
}

/** Reads the database through [database] on every call, so a database that was replaced is picked up. */
class SqlDelightFireTelemetryRepository(
    private val database: () -> AndroidStoreDatabase,
) : FireTelemetryRepository {
    override fun insert(telemetry: FireTelemetry): Boolean =
        nonFatal(false) {
            database().fireTelemetryQueries.insertFireTelemetry(
                event_id = telemetry.eventId,
                resolved_tier = telemetry.resolvedTier.name,
                screen_on = telemetry.screenOn?.toLong(),
                audio_focus_obtained = telemetry.audioFocusObtained?.toLong(),
                battery_pct = telemetry.batteryPct?.toLong(),
                doze_state = telemetry.dozeState,
                watchdog_repair = telemetry.watchdogRepair.toLong(),
                boot_count = telemetry.bootCount,
            )
            true
        }

    override fun findForEvent(eventId: String): FireTelemetry? =
        nonFatal(null) {
            database().fireTelemetryQueries.selectFireTelemetryForEvent(eventId).executeAsOneOrNull()?.let {
                FireTelemetry(
                    eventId = it.event_id,
                    resolvedTier = DeliveryCapability.valueOf(it.resolved_tier),
                    screenOn = it.screen_on?.let { value -> value != 0L },
                    audioFocusObtained = it.audio_focus_obtained?.let { value -> value != 0L },
                    batteryPct = it.battery_pct?.toInt(),
                    dozeState = it.doze_state,
                    watchdogRepair = it.watchdog_repair != 0L,
                    bootCount = it.boot_count,
                )
            }
        }

    override fun count(): Long = nonFatal(0L) { database().fireTelemetryQueries.countFireTelemetry().executeAsOne() }

    private fun Boolean.toLong() = if (this) 1L else 0L
}
