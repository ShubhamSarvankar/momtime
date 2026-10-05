package com.momtime.android.reliability

import android.content.res.Resources
import com.momtime.android.R
import com.momtime.android.store.CheckOutcome
import com.momtime.android.store.ReliabilityCheck
import kotlin.time.Instant

/**
 * The check screen's sentences (ADR 0070): the status of the test she started, and the report as plain lines, with
 * plurals and nothing built by concatenation. English only in Phase 2; every string is a resource.
 */
internal object ReliabilityText {
    fun status(
        resources: Resources,
        pending: ReliabilityCheck?,
        last: ReliabilityCheck?,
        now: Instant,
    ): String =
        when {
            pending != null -> running(resources, (pending.scheduledAt - now).inWholeSeconds.toInt())
            last?.outcome == CheckOutcome.FIRED -> {
                val late = ((last.firedAt ?: last.scheduledAt) - last.scheduledAt).inWholeSeconds.coerceAtLeast(0)
                resources.getQuantityString(R.plurals.check_status_fired, late.toInt(), late.toInt())
            }
            last?.outcome == CheckOutcome.MISSED -> resources.getString(R.string.check_status_missed)
            else -> resources.getString(R.string.check_status_none)
        }

    private fun running(
        resources: Resources,
        seconds: Int,
    ): String =
        if (seconds >= 1) {
            resources.getQuantityString(R.plurals.check_status_running, seconds, seconds)
        } else {
            resources.getString(R.string.check_status_due)
        }

    fun report(
        resources: Resources,
        report: ReliabilityReport,
    ): String {
        val lines = mutableListOf<String>()
        lines +=
            when (report.lastSettledCheck?.outcome) {
                CheckOutcome.FIRED -> resources.getString(R.string.report_last_check_fired)
                CheckOutcome.MISSED -> resources.getString(R.string.report_last_check_missed)
                else -> resources.getString(R.string.report_last_check_none)
            }
        if (report.drift.isEmpty()) lines += resources.getString(R.string.report_no_fires)
        report.drift.forEach {
            lines +=
                resources.getQuantityString(
                    R.plurals.report_tier_line,
                    it.fires,
                    it.tier.ordinal + 1,
                    it.fires,
                    it.median.inWholeSeconds,
                    it.slowest.inWholeSeconds,
                )
        }
        lines += counted(resources, R.plurals.report_never_fired, report.neverFired)
        lines += counted(resources, R.plurals.report_missed, report.missedOccurrences)
        lines += counted(resources, R.plurals.report_repairs, report.watchdogRepairs)
        lines +=
            counted(
                resources,
                R.plurals.report_store_failures,
                report.storeFailures.values
                    .sum()
                    .toInt(),
            )
        if (report.corruption != null) lines += resources.getString(R.string.report_corruption)
        lines += counted(resources, R.plurals.report_unseen_boots, report.unseenBoots)
        if (report.unusedAppExempt == false) lines += resources.getString(R.string.report_unused_app_restrictions)
        lines += counted(resources, R.plurals.report_unobserved, report.windowDays - report.daysWithFires)
        return lines.joinToString("\n")
    }

    private fun counted(
        resources: Resources,
        plural: Int,
        count: Int,
    ) = resources.getQuantityString(plural, count, count)
}
