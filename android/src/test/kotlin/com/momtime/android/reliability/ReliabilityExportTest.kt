package com.momtime.android.reliability

import android.content.Context
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.t0
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.EventType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The export (ADR 0070). It is a document she hands to someone, so what it must not carry is tested by planting it:
 * a medicine name, a dose, doctor's instructions, an occurrence id, a template id, an event id, and the instant a
 * reminder fired are all in the database the export is built from, and none of them may be in the file. A document with
 * no identifier and no text she typed is also what invariant 11 asks of a log.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class ReliabilityExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val device = DeviceInfo(model = "Pixel 7", sdk = 36, versionName = "0.1.0", versionCode = 1)

    @After
    fun tearDown() = fixture.close()

    /** A fire of an occurrence that carries a name, a dose and an instruction, and a check that fired. */
    private fun planted(): ReliabilityReport {
        fixture.seed(
            "occ-secret-7",
            Criticality.CRITICAL,
            t0,
            slot = 31,
            dosage = "SENTINEL-DOSE 400 micrograms",
            doctorInstructions = "SENTINEL-INSTRUCTIONS take after food",
        )
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.clock.now = t0 + 12.seconds
        fixture.handler.onFire(31, t0)
        val fired = fixture.eventsOf("occ-secret-7", EventType.ALARM_FIRED).first().id
        fixture.graph.get<FireTelemetryRepository>().insert(
            FireTelemetry(
                eventId = fired,
                resolvedTier = DeliveryCapability.TIER_3,
                screenOn = true,
                audioFocusObtained = null,
                batteryPct = 64,
                dozeState = "IDLE",
                watchdogRepair = false,
                bootCount = fixture.bootCount,
                deliveryPath = "RING",
                ringerStarted = true,
                alarmStreamMuted = false,
                clockChanges = 0,
            ),
        )
        val runner = fixture.graph.get<CanaryRunner>()
        val check = (runner.start() as StartResult.Started).check
        fixture.clock.now = check.scheduledAt + 7.seconds
        runner.onFired(check.id)
        return fixture.graph.get<ReliabilityReader>().read(fixture.clock.now)
    }

    @Test
    fun `the export carries the timing and the device state, and none of what she typed or what identifies anything`() {
        val json = ReliabilityExport.toJson(planted(), device)

        val secrets =
            listOf(
                "Iron tablet", // the template's title: a medicine name
                "SENTINEL-DOSE",
                "SENTINEL-INSTRUCTIONS",
                "occ-secret-7", // an occurrence id
                "tmpl-occ-secret-7", // a template id
                "gen-", // every event id of the fixture
                "Prenatal",
            )
        for (secret in secrets) assertFalse("the export must not contain \"$secret\"", json.contains(secret))
        // The times she takes her medicine are not in the file: a fire is dated by its day number alone.
        assertFalse(json.contains(t0.toEpochMilliseconds().toString().take(9)))
        assertFalse(json.contains("2026-03-01T08"))
        assertFalse("no key names a time of day", Regex("\"(firedAt|scheduledAt|time)\"").containsMatchIn(json))
    }

    @Test
    fun `the export is one versioned document with the build, the phone, the figures and the check`() {
        val doc = JSONObject(ReliabilityExport.toJson(planted(), device))

        assertEquals(1, doc.getInt("schemaVersion"))
        assertEquals(ReliabilityExport.SCHEMA_VERSION, doc.getInt("schemaVersion"))
        assertEquals("Pixel 7", doc.getJSONObject("device").getString("model"))
        assertEquals(36, doc.getJSONObject("device").getInt("sdk"))
        assertEquals("0.1.0", doc.getJSONObject("app").getString("versionName"))
        assertEquals(7, doc.getInt("windowDays"))
        assertEquals(1, doc.getInt("daysWithFires"))

        val fire = doc.getJSONArray("fires").getJSONObject(0)
        assertEquals("TIER_3", fire.getString("tier"))
        assertEquals(12_000, fire.getLong("latencyMs"))
        assertEquals(t0.toEpochMilliseconds() / 86_400_000L, fire.getLong("day"))
        assertEquals(true, fire.getBoolean("screenOn"))
        assertTrue(fire.isNull("audioFocus"))
        assertEquals(64, fire.getInt("batteryPct"))
        assertEquals("IDLE", fire.getString("doze"))
        assertEquals("RING", fire.getString("deliveryPath"))

        val drift = doc.getJSONArray("drift").getJSONObject(0)
        assertEquals("TIER_3", drift.getString("tier"))
        assertEquals(1, drift.getInt("fires"))
        assertEquals(12_000, drift.getLong("medianMs"))
        assertEquals(12_000, drift.getLong("slowestMs"))

        val check = doc.getJSONArray("checks").getJSONObject(0)
        assertEquals("FIRED", check.getString("outcome"))
        assertEquals(7_000, check.getLong("latencyMs"))

        assertEquals(0, doc.getInt("neverFired"))
        assertEquals(0, doc.getInt("catchUp"))
        assertEquals(0, doc.getJSONObject("excluded").length())
        assertEquals(false, doc.getBoolean("corruptionFound"))
    }

    @Test
    fun `an empty report exports an empty document of the same shape`() {
        val report = fixture.graph.get<ReliabilityReader>().read(t0)

        val doc = JSONObject(ReliabilityExport.toJson(report, device))

        assertEquals(0, doc.getJSONArray("fires").length())
        assertEquals(0, doc.getJSONArray("drift").length())
        assertEquals(0, doc.getJSONArray("checks").length())
        assertEquals(0, doc.getInt("daysWithFires"))
        assertEquals(0, doc.getJSONObject("storeFailures").length())
    }

    @Test
    fun `a check that never fired exports no latency`() {
        val runner = fixture.graph.get<CanaryRunner>()
        val check = (runner.start() as StartResult.Started).check
        fixture.clock.now = check.scheduledAt + CheckPolicy.TIMEOUT
        runner.settleOverdue()

        val doc =
            JSONObject(ReliabilityExport.toJson(fixture.graph.get<ReliabilityReader>().read(fixture.clock.now), device))

        val exported = doc.getJSONArray("checks").getJSONObject(0)
        assertEquals("MISSED", exported.getString("outcome"))
        assertTrue(exported.isNull("latencyMs"))
        assertEquals(60.seconds.inWholeMilliseconds, CheckPolicy.DELAY.inWholeMilliseconds)
    }
}
