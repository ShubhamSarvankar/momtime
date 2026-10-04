package com.momtime.android.arming

import com.momtime.android.di.BootCount
import com.momtime.android.store.ArmedAlarm
import com.momtime.shared.engine.RungSelection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Why the watchdog believes the armed alarm was lost (ADR 0058). Each is positive evidence on its own. */
enum class RepairEvidence {
    /** The android store has no armed record, although a rung is expected. */
    RECORD_MISSING,

    /** The armed record is for another slot or another rung than the one the domain expects. */
    RECORD_MISMATCH,

    /** The `PendingIntent` probe (`FLAG_NO_CREATE`) for the expected slot found nothing. */
    ALARM_ABSENT,

    /** The boot count differs from the one recorded when the alarm was armed: the device restarted. */
    BOOT_COUNT_CHANGED,

    /** Exact alarm capability differs from what it was when the alarm was armed. */
    EXACT_CAPABILITY_CHANGED,

    /** The app was updated after the alarm was armed, and an update clears the app's alarms. */
    APP_UPDATED,

    /** The expected rung is overdue beyond the tolerance for the mechanism that was armed. */
    RUNG_OVERDUE,
}

/** What one watchdog pass saw, before it changed anything. */
internal data class WatchdogObservation(
    val expected: RungSelection,
    val armed: ArmedAlarm?,
    /** Whether the system still holds the `PendingIntent` for the expected slot. */
    val alarmPresent: Boolean,
    val bootCountNow: Long,
    val exactAllowedNow: Boolean,
    /** When the app was last installed or updated, if the platform says. */
    val appUpdatedAt: Instant?,
    val now: Instant,
)

/**
 * The evidence that the armed alarm was lost (decision 6 of the Phase 2 progress file, ADR 0058). Pure.
 *
 * It looks for positive evidence and writes nothing: a pass over a correct state finds none (golden scenario
 * 17), and `WATCHDOG_REPAIR` is written only when this returns something. The expected rung is the first one
 * that has not fired (selection reads the record of fired rungs), so an overdue rung here is by construction
 * one with no `ALARM_FIRED`.
 *
 * How late a rung may be before lateness counts as evidence depends on the mechanism that was armed. An exact
 * alarm (`setAlarmClock`) fires within seconds, so a couple of minutes late means it did not fire. An inexact
 * `setAndAllowWhileIdle` alarm (Tier 1) may legitimately run late: in Doze it is deferred to a maintenance
 * window and limited to about one fire per app per nine minutes, so its tolerance is 15 minutes, which is
 * also the watchdog's own period. Both are inside the 30 minute catch up window (ADR 0056); lateness beyond
 * it is not repaired by ringing, because selection no longer offers that rung.
 */
internal object WatchdogEvidence {
    val EXACT_TOLERANCE: Duration = 2.minutes
    val INEXACT_TOLERANCE: Duration = 15.minutes

    fun of(observation: WatchdogObservation): Set<RepairEvidence> =
        buildSet {
            val armed = observation.armed
            val expected = observation.expected
            if (armed == null) {
                add(RepairEvidence.RECORD_MISSING)
            } else {
                if (armed.alarmSlot != expected.alarmSlot || armed.rungInstant != expected.rung.instant) {
                    add(RepairEvidence.RECORD_MISMATCH)
                }
                // A boot count the platform did not report, at either end, says nothing about a restart.
                if (armed.bootCount != BootCount.UNKNOWN &&
                    observation.bootCountNow != BootCount.UNKNOWN &&
                    armed.bootCount != observation.bootCountNow
                ) {
                    add(RepairEvidence.BOOT_COUNT_CHANGED)
                }
                if (armed.exactAllowed != observation.exactAllowedNow) add(RepairEvidence.EXACT_CAPABILITY_CHANGED)
                val updatedAt = observation.appUpdatedAt
                if (updatedAt != null && updatedAt > armed.armedAt) add(RepairEvidence.APP_UPDATED)
            }
            if (!observation.alarmPresent) add(RepairEvidence.ALARM_ABSENT)
            // What was armed decides how late is too late. With no record there is nothing to go by, and the
            // missing record is evidence already, so the capability now stands in.
            val exact = armed?.exactAllowed ?: observation.exactAllowedNow
            val tolerance = if (exact) EXACT_TOLERANCE else INEXACT_TOLERANCE
            if (observation.now - expected.rung.instant > tolerance) add(RepairEvidence.RUNG_OVERDUE)
        }
}
