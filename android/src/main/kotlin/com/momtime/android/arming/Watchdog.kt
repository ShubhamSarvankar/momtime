package com.momtime.android.arming

import android.content.Context
import com.momtime.android.capability.DeliveryMechanism
import com.momtime.android.di.BootCount
import com.momtime.android.di.Uptime
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.shared.data.ReconcileCommand

/** Asks the system whether the alarm for a slot is still armed, without creating one (`FLAG_NO_CREATE`). */
internal fun interface AlarmProbe {
    fun isArmed(slot: Int): Boolean
}

/** The platform's answer: the `PendingIntent` for the slot exists. Nothing is created by asking. */
internal class PlatformAlarmProbe(
    private val context: Context,
) : AlarmProbe {
    override fun isArmed(slot: Int): Boolean = AlarmIntents.existing(context, slot) != null
}

/** The app's version code, or [AppVersion.UNKNOWN] if the platform does not say. */
internal fun interface AppVersion {
    fun versionCode(): Long

    companion object {
        const val UNKNOWN = -1L
    }
}

/**
 * `PackageInfo.longVersionCode`. An update replaces the package, which clears the app's alarms, and the armed
 * record carries the version code it was armed under, so an update since is a different number. It does not
 * depend on the wall clock, which `lastUpdateTime` compared with `armedAt` did (ADR 0058).
 */
internal class PlatformAppVersion(
    private val context: Context,
) : AppVersion {
    @Suppress("SwallowedException")
    override fun versionCode(): Long =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            AppVersion.UNKNOWN
        }
}

/** The three things the watchdog asks the platform, each through a seam a test can replace. */
internal class PlatformProbes(
    val alarm: AlarmProbe,
    val bootCount: BootCount,
    val appVersion: AppVersion,
    val uptime: Uptime,
)

/** What one watchdog pass did. */
data class WatchdogResult(
    /** How many occurrences `Reconcile` derived to `MISSED`. */
    val missed: Int,
    /** The positive evidence found. Empty means the armed alarm was correct and `WATCHDOG_REPAIR` was not written. */
    val evidence: Set<RepairEvidence>,
    val ensured: EnsureResult,
)

/**
 * One watchdog pass (ARCHITECTURE.md section 5.3, ADR 0058), run by the `WorkManager` job every 15 minutes.
 *
 * 1. Dispatch `Reconcile`, so an occurrence whose grace expired while the app was closed is derived to `MISSED`
 *    even if no alarm ever fires for it.
 * 2. Resolve capability and compare what the system and the armed record say with the rung the domain expects.
 * 3. Write `WATCHDOG_REPAIR` only if there is positive evidence that the armed alarm was lost.
 * 4. Call `ensureArmed`, which is idempotent: on a correct state it replaces the alarm with identical
 *    parameters and writes nothing (golden scenario 17).
 *
 * It never starts the ringer. A rung found overdue within the catch up window is armed for now, and the alarm
 * path rings it when it fires (ADR 0056). The pass runs exclusive of the fire path, so it never reads a fire
 * that is half done as a lost alarm.
 */
class Watchdog internal constructor(
    private val reconcile: ReconcileCommand,
    private val coordinator: ArmingCoordinator,
    private val log: AlarmLog,
    private val armed: ArmedAlarmRepository,
    private val resolver: CapabilityResolver,
    private val probes: PlatformProbes,
) {
    fun run(): WatchdogResult =
        coordinator.exclusive {
            val missed = reconcile.dispatch(log.now())
            // Capability is read before anything is armed, so what the armed record says is compared with the
            // platform as it is now. The pass below reads it again, and arms through that.
            val capability = resolver.resolve()
            // With no rung expected there is nothing to have lost, so there is no evidence to look for.
            val expected = coordinator.expected()
            val evidence =
                if (expected == null) {
                    emptySet()
                } else {
                    WatchdogEvidence.of(
                        WatchdogObservation(
                            expected = expected,
                            armed = armed.current(),
                            alarmPresent = probes.alarm.isArmed(expected.alarmSlot),
                            bootCountNow = probes.bootCount.read(),
                            exactAllowedNow = capability.mechanism == DeliveryMechanism.SET_ALARM_CLOCK,
                            appVersionNow = probes.appVersion.versionCode(),
                            now = log.now(),
                        ),
                    )
                }
            if (expected != null && evidence.isNotEmpty()) log.watchdogRepair(expected.occurrenceId)
            WatchdogResult(missed, evidence, coordinator.ensureArmed())
        }
}
