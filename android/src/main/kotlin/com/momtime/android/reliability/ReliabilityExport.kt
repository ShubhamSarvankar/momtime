package com.momtime.android.reliability

import com.momtime.android.store.ReliabilityCheck
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Instant

/** The phone and the build, as the platform names them. A model is a type of phone, not a person (ADR 0070). */
data class DeviceInfo(
    val model: String,
    val sdk: Int,
    val versionName: String,
    val versionCode: Long,
)

/**
 * The reliability report as one JSON document (ADR 0070), for her to hand to whoever is fixing her phone's reminders.
 * She chooses where the file goes (the system's document picker), and nothing is sent anywhere.
 *
 * What it carries: the build and the phone model, how late each counted reminder fire was (with its tier and what the
 * device was doing), the counts the report shows, the check's history and the store's health. What it never carries,
 * by construction and by a test that plants a medicine name and a note and looks for them: any identifier (an event,
 * an occurrence, a template), any text she typed, a dose, an instruction, a weight, and the time of day of any
 * reminder. A fire is dated by its day number alone, so the times she takes her medicine are not in the file.
 * Invariant 11 applies to a file as it does to a log.
 *
 * Written with the platform's `org.json`, which is part of Android: no dependency is added (invariant 7).
 */
object ReliabilityExport {
    const val SCHEMA_VERSION = 1
    const val MIME_TYPE = "application/json"
    const val FILE_NAME = "momtime-reliability.json"

    private const val DAY_MILLIS = 86_400_000L

    fun toJson(
        report: ReliabilityReport,
        device: DeviceInfo,
    ): String =
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedDay", day(report.asOf))
            .put("app", JSONObject().put("versionName", device.versionName).put("versionCode", device.versionCode))
            .put("device", JSONObject().put("model", device.model).put("sdk", device.sdk))
            .put("windowDays", report.windowDays)
            .put("daysWithFires", report.daysWithFires)
            .put("drift", JSONArray(report.drift.map(::tierDrift)))
            .put("fires", JSONArray(report.fires.map(::fireRow)))
            .put("catchUp", report.catchUp)
            .put("excluded", counts(report.excluded.mapKeys { it.key.name }))
            .put("neverFired", report.neverFired)
            .put("neverFiredExcluded", counts(report.neverFiredExcluded.mapKeys { it.key.name }))
            .put("missedOccurrences", report.missedOccurrences)
            .put("watchdogRepairs", report.watchdogRepairs)
            .put("mutedFires", report.mutedFires)
            .put("storeFailures", counts(report.storeFailures))
            .put("corruptionFound", report.corruption != null)
            .put("checks", JSONArray(report.checks.map(::check)))
            .toString()

    private fun tierDrift(drift: TierDrift) =
        JSONObject()
            .put("tier", drift.tier.name)
            .put("fires", drift.fires)
            .put("medianMs", drift.median.inWholeMilliseconds)
            .put("slowestMs", drift.slowest.inWholeMilliseconds)

    private fun fireRow(row: FireRow): JSONObject {
        val t = row.telemetry
        return JSONObject()
            .put("day", day(row.firedAt))
            .put("tier", row.tier.name)
            .put("latencyMs", row.latency.inWholeMilliseconds)
            .put("screenOn", t.screenOn ?: JSONObject.NULL)
            .put("audioFocus", t.audioFocusObtained ?: JSONObject.NULL)
            .put("batteryPct", t.batteryPct ?: JSONObject.NULL)
            .put("doze", t.dozeState ?: JSONObject.NULL)
            .put("watchdogRepair", t.watchdogRepair)
            .put("deliveryPath", t.deliveryPath ?: JSONObject.NULL)
            .put("ringerStarted", t.ringerStarted ?: JSONObject.NULL)
            .put("alarmStreamMuted", t.alarmStreamMuted ?: JSONObject.NULL)
    }

    private fun check(check: ReliabilityCheck): JSONObject =
        JSONObject()
            .put("day", day(check.scheduledAt))
            .put("outcome", check.outcome.name)
            .put("tier", check.resolvedTier.name)
            .put("latencyMs", check.firedAt?.let { (it - check.scheduledAt).inWholeMilliseconds } ?: JSONObject.NULL)

    private fun counts(values: Map<String, Number>) =
        JSONObject().also { json -> values.forEach { (k, v) -> json.put(k, v) } }

    /** Days since the epoch (UTC): a date and no time of day. */
    private fun day(at: Instant): Long = Math.floorDiv(at.toEpochMilliseconds(), DAY_MILLIS)
}
