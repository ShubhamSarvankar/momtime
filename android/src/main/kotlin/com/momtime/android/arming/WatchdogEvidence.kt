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

    /** The app's version code differs from the one the alarm was armed under: an update clears the app's alarms. */
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
    /** The app's version code now, or [AppVersion.UNKNOWN]. */
    val appVersionNow: Long,
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
 * also the watchdog's own period. A rung overdue beyond the 30 minute catch up window is still armed for now
 * and delivered as a silent notice by the fire path (ADR 0056), so it is evidence like any other overdue rung.
 */
internal object WatchdogEvidence {
    val EXACT_TOLERANCE: Duration = 2.minutes
    val INEXACT_TOLERANCE: Duration = 15.minutes

    fun of(observation: WatchdogObservation): Set<RepairEvidence> =
        buildSet {
            val armed = observation.armed
            if (armed == null) {
                add(RepairEvidence.RECORD_MISSING)
            } else {
                addAll(againstRecord(armed, observation))
            }
            if (!observation.alarmPresent) add(RepairEvidence.ALARM_ABSENT)
            // What was armed decides how late is too late. With no record there is nothing to go by, and the
            // missing record is evidence already, so the capability now stands in.
            val exact = armed?.exactAllowed ?: observation.exactAllowedNow
            val tolerance = if (exact) EXACT_TOLERANCE else INEXACT_TOLERANCE
            if (observation.now - observation.expected.rung.instant > tolerance) add(RepairEvidence.RUNG_OVERDUE)
        }

    /** What the record says against what the platform says now. */
    private fun againstRecord(
        armed: ArmedAlarm,
        observation: WatchdogObservation,
    ): Set<RepairEvidence> =
        buildSet {
            val expected = observation.expected
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
            // A version the platform did not report, at either end, says nothing about an update.
            if (armed.versionCode != AppVersion.UNKNOWN &&
                observation.appVersionNow != AppVersion.UNKNOWN &&
                armed.versionCode != observation.appVersionNow
            ) {
                add(RepairEvidence.APP_UPDATED)
            }
        }
}
