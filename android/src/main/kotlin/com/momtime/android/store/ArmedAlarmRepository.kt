package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase
import kotlin.time.Instant

/**
 * The alarm this app believes it armed (ADR 0048, ADR 0017). The watchdog compares it with the rung
 * the domain says should be next. [bootCount] and [exactAllowed] are what the device reported when
 * the alarm was armed, so a reboot or a change in exact alarm capability since is visible. [versionCode] is the
 * app's version code then: an update replaces the package and clears its alarms, and a different version code
 * now says so without depending on the wall clock (ADR 0058).
 */
data class ArmedAlarm(
    val alarmSlot: Int,
    val rungInstant: Instant,
    val armedAt: Instant,
    val bootCount: Long,
    val exactAllowed: Boolean,
    val versionCode: Long,
)

/**
 * No call throws in production (ADR 0051, ADR 0054). A failed read is "record missing", which sends the
 * watchdog to its repair path, and a failed write returns false; neither may stop an alarm. Each failure is
 * counted and logged by [StoreFailures], and in tests it is thrown.
 */
interface ArmedAlarmRepository {
    /** The record, or null if there is none or the store failed. */
    fun current(): ArmedAlarm?

    /**
     * Replaces the record. At most one alarm is armed at a time, so there is at most one record.
     * True if it was stored.
     */
    fun replace(alarm: ArmedAlarm): Boolean

    /** True if the record is gone. */
    fun clear(): Boolean
}

/** Reads the database through [database] on every call, so a database that was replaced is picked up. */
class SqlDelightArmedAlarmRepository(
    private val database: () -> AndroidStoreDatabase,
    private val failures: StoreFailures,
) : ArmedAlarmRepository {
    override fun current(): ArmedAlarm? =
        nonFatal(failures, "armed_alarm.current", null) {
            database().armedAlarmQueries.selectArmedAlarm().executeAsOneOrNull()?.let {
                ArmedAlarm(
                    alarmSlot = it.alarm_slot.toInt(),
                    rungInstant = Instant.fromEpochMilliseconds(it.rung_instant),
                    armedAt = Instant.fromEpochMilliseconds(it.armed_at),
                    bootCount = it.boot_count,
                    exactAllowed = it.exact_allowed != 0L,
                    versionCode = it.version_code,
                )
            }
        }

    override fun replace(alarm: ArmedAlarm): Boolean =
        nonFatal(failures, "armed_alarm.replace", false) {
            database().armedAlarmQueries.replaceArmedAlarm(
                alarm_slot = alarm.alarmSlot.toLong(),
                rung_instant = alarm.rungInstant.toEpochMilliseconds(),
                armed_at = alarm.armedAt.toEpochMilliseconds(),
                boot_count = alarm.bootCount,
                exact_allowed = if (alarm.exactAllowed) 1L else 0L,
                version_code = alarm.versionCode,
            )
            true
        }

    override fun clear(): Boolean =
        nonFatal(failures, "armed_alarm.clear", false) {
            database().armedAlarmQueries.clearArmedAlarm()
            true
        }
}
