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
    /** Which delivery path the fire took (ADR 0060). Null for a row written before it was kept. */
    val deliveryPath: String? = null,
    /** Whether the ringer service started: false when the platform refused it. Null if it was not attempted. */
    val ringerStarted: Boolean? = null,
    /** Whether the alarm stream was muted (volume zero) at the fire: a ring she could not hear. Null if not read. */
    val alarmStreamMuted: Boolean? = null,
    /** How many times the clock had been set when the alarm fired (ADR 0070). Null for a row from before. */
    val clockChanges: Long? = null,
)

/**
 * No call throws in production (ADR 0051, ADR 0054): a failure is counted and logged and returns false, null
 * or zero, because a lost telemetry row must never stop an alarm. In tests it is thrown.
 */
interface FireTelemetryRepository {
    /** True if the row was stored. */
    fun insert(telemetry: FireTelemetry): Boolean

    /**
     * Records what the ringer did for a fire: whether it started, and whether audio focus was obtained (null if
     * it did not get that far). True if the row was updated.
     */
    fun recordRinger(
        eventId: String,
        started: Boolean,
        audioFocus: Boolean?,
    ): Boolean

    /** The row, or null if there is none or the store failed. */
    fun findForEvent(eventId: String): FireTelemetry?

    /** The number of rows, or zero if the store failed. */
    fun count(): Long
}

/** Reads the database through [database] on every call, so a database that was replaced is picked up. */
class SqlDelightFireTelemetryRepository(
    private val database: () -> AndroidStoreDatabase,
    private val failures: StoreFailures,
) : FireTelemetryRepository {
    override fun insert(telemetry: FireTelemetry): Boolean =
        nonFatal(failures, "fire_telemetry.insert", false) {
            database().fireTelemetryQueries.insertFireTelemetry(
                event_id = telemetry.eventId,
                resolved_tier = telemetry.resolvedTier.name,
                screen_on = telemetry.screenOn?.toLong(),
                audio_focus_obtained = telemetry.audioFocusObtained?.toLong(),
                battery_pct = telemetry.batteryPct?.toLong(),
                doze_state = telemetry.dozeState,
                watchdog_repair = telemetry.watchdogRepair.toLong(),
                boot_count = telemetry.bootCount,
                delivery_path = telemetry.deliveryPath,
                ringer_started = telemetry.ringerStarted?.toLong(),
                alarm_stream_muted = telemetry.alarmStreamMuted?.toLong(),
                clock_changes = telemetry.clockChanges,
            )
            true
        }

    override fun recordRinger(
        eventId: String,
        started: Boolean,
        audioFocus: Boolean?,
    ): Boolean =
        nonFatal(failures, "fire_telemetry.ringer", false) {
            database().fireTelemetryQueries.updateRinger(started.toLong(), audioFocus?.toLong(), eventId)
            true
        }

    override fun findForEvent(eventId: String): FireTelemetry? =
        nonFatal(failures, "fire_telemetry.find", null) {
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
                    deliveryPath = it.delivery_path,
                    ringerStarted = it.ringer_started?.let { value -> value != 0L },
                    alarmStreamMuted = it.alarm_stream_muted?.let { value -> value != 0L },
                    clockChanges = it.clock_changes,
                )
            }
        }

    override fun count(): Long =
        nonFatal(failures, "fire_telemetry.count", 0L) {
            database().fireTelemetryQueries.countFireTelemetry().executeAsOne()
        }

    private fun Boolean.toLong() = if (this) 1L else 0L
}
