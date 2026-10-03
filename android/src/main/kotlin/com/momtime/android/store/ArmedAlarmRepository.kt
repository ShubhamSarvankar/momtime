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

interface ArmedAlarmRepository {
    fun current(): ArmedAlarm?

    /** Replaces the record. At most one alarm is armed at a time, so there is at most one record. */
    fun replace(alarm: ArmedAlarm)

    fun clear()
}

class SqlDelightArmedAlarmRepository(
    database: AndroidStoreDatabase,
) : ArmedAlarmRepository {
    private val queries = database.armedAlarmQueries

    override fun current(): ArmedAlarm? =
        queries.selectArmedAlarm().executeAsOneOrNull()?.let {
            ArmedAlarm(
                alarmSlot = it.alarm_slot.toInt(),
                rungInstant = Instant.fromEpochMilliseconds(it.rung_instant),
                armedAt = Instant.fromEpochMilliseconds(it.armed_at),
                bootCount = it.boot_count,
                exactAllowed = it.exact_allowed != 0L,
            )
        }

    override fun replace(alarm: ArmedAlarm) {
        queries.replaceArmedAlarm(
            alarm_slot = alarm.alarmSlot.toLong(),
            rung_instant = alarm.rungInstant.toEpochMilliseconds(),
            armed_at = alarm.armedAt.toEpochMilliseconds(),
            boot_count = alarm.bootCount,
            exact_allowed = if (alarm.exactAllowed) 1L else 0L,
        )
    }

    override fun clear() {
        queries.clearArmedAlarm()
    }
}
