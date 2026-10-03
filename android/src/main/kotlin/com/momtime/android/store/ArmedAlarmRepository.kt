package com.momtime.android.store

import com.momtime.android.store.db.AndroidStoreDatabase
import kotlin.time.Instant

/**
 * The alarm this app believes it armed (ADR 0048, ADR 0017). The watchdog compares it with the rung
 * the domain says should be next. [bootCount] and [exactAllowed] are what the device reported when
 * the alarm was armed, so a reboot or a change in exact alarm capability since is visible.
 */
data class ArmedAlarm(
    val alarmSlot: Int,
    val rungInstant: Instant,
    val armedAt: Instant,
    val bootCount: Long,
    val exactAllowed: Boolean,
)

/**
 * No call throws (ADR 0051). A failed read is "record missing", which sends the watchdog to its repair
 * path, and a failed write returns false; neither may stop an alarm.
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
) : ArmedAlarmRepository {
    override fun current(): ArmedAlarm? =
        nonFatal(null) {
            database().armedAlarmQueries.selectArmedAlarm().executeAsOneOrNull()?.let {
                ArmedAlarm(
                    alarmSlot = it.alarm_slot.toInt(),
                    rungInstant = Instant.fromEpochMilliseconds(it.rung_instant),
                    armedAt = Instant.fromEpochMilliseconds(it.armed_at),
                    bootCount = it.boot_count,
                    exactAllowed = it.exact_allowed != 0L,
                )
            }
        }

    override fun replace(alarm: ArmedAlarm): Boolean =
        nonFatal(false) {
            database().armedAlarmQueries.replaceArmedAlarm(
                alarm_slot = alarm.alarmSlot.toLong(),
                rung_instant = alarm.rungInstant.toEpochMilliseconds(),
                armed_at = alarm.armedAt.toEpochMilliseconds(),
                boot_count = alarm.bootCount,
                exact_allowed = if (alarm.exactAllowed) 1L else 0L,
            )
            true
        }

    override fun clear(): Boolean =
        nonFatal(false) {
            database().armedAlarmQueries.clearArmedAlarm()
            true
        }
}
