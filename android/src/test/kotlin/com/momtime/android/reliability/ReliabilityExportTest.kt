package com.momtime.android.reliability

import android.content.Context
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.t0
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.EventType
import kotlinx.datetime.LocalDate
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
import kotlin.time.Duration.Companion.minutes
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
        // No timestamp is in the file: a fire is dated by its local date and hour, and the hour is all there is.
        assertFalse(json.contains(t0.toEpochMilliseconds().toString().take(9)))
        assertFalse(json.contains("2026-03-01T08"))
        assertFalse("no key names a timestamp", Regex("\"(firedAt|scheduledAt|time|timestamp)\"").containsMatchIn(json))
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
        assertEquals(LocalDate(2026, 3, 1).toEpochDays(), fire.getLong("day"))
        assertEquals("13:30 in the fixture's zone, the hour only", 13, fire.getInt("rungHour"))
        assertEquals(13, fire.getInt("fireHour"))
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

    /** A fire due [rungOffset] after 08:00Z, fired 12 seconds late, exported as in [planted]. */
    private fun exportOfFireDueAfter(rungOffset: kotlin.time.Duration): String {
        val other = ArmingFixture(context)
        try {
            val rung = t0 + rungOffset
            other.seed("occ-secret-7", Criticality.CRITICAL, rung, slot = 31)
            other.clock.now = rung - 1.hours
            other.coordinator.ensureArmed()
            other.clock.now = rung + 12.seconds
            other.handler.onFire(31, rung)
            val fired = other.eventsOf("occ-secret-7", EventType.ALARM_FIRED).first().id
            other.graph.get<FireTelemetryRepository>().insert(
                FireTelemetry(
                    fired,
                    DeliveryCapability.TIER_3,
                    true,
                    null,
                    64,
                    "IDLE",
                    false,
                    other.bootCount,
                    "RING",
                    true,
                    false,
                    0,
                ),
            )
            return ReliabilityExport.toJson(other.graph.get<ReliabilityReader>().read(rung + 1.hours), device)
        } finally {
            other.close()
        }
    }

    // Two fires in the same local hour that differ in minutes and seconds export identically: nothing finer than the
    // hour is in the file. 13:30:00 and 13:59:41 in the fixture's zone are both hour 13.
    @Test
    fun `fires in the same hour export alike, so no field has sub hour resolution`() {
        val early = exportOfFireDueAfter(0.seconds)
        val late = exportOfFireDueAfter(29.minutes + 41.seconds)

        assertEquals(early, late)
        val fire = JSONObject(early).getJSONArray("fires").getJSONObject(0)
        assertEquals(13, fire.getInt("fireHour"))
        assertEquals(13, fire.getInt("rungHour"))
    }

    @Test
    fun `an hour later exports as the next hour`() {
        val first = JSONObject(exportOfFireDueAfter(0.seconds)).getJSONArray("fires").getJSONObject(0)
        val next = JSONObject(exportOfFireDueAfter(1.hours)).getJSONArray("fires").getJSONObject(0)

        assertEquals(first.getInt("fireHour") + 1, next.getInt("fireHour"))
    }

    @Test
    fun `every hour field is a whole hour of the day and every day field a whole day`() {
        val doc = JSONObject(ReliabilityExport.toJson(planted(), device))
        val fire = doc.getJSONArray("fires").getJSONObject(0)

        for (key in listOf("rungHour", "fireHour")) {
            val value = fire.get(key)
            assertTrue("$key must be an integer, was $value", value is Int)
            assertTrue("$key must be an hour of the day", (value as Int) in 0..23)
        }
        assertTrue(fire.get("day") is Number)
        assertTrue("a day number is far smaller than an instant in milliseconds", fire.getLong("day") < 1_000_000L)
        assertEquals(0, doc.getJSONArray("neverFiredRungs").length())
    }

    @Test
    fun `the export carries the unseen boot count and the unused app restrictions, and no timestamp`() {
        fixture.graph.get<com.momtime.android.store.BootInstantRepository>().apply {
            record(
                com.momtime.android.store
                    .BootInstant(7, t0 - 5.hours),
            )
            record(
                com.momtime.android.store
                    .BootInstant(10, t0 - 1.hours),
            )
        }
        fixture.unusedAppExempt = false

        val json = ReliabilityExport.toJson(fixture.graph.get<ReliabilityReader>().read(t0), device)
        val doc = JSONObject(json)

        assertEquals("a count: two boots lie between 7 and 10", 2, doc.getInt("unseenBoots"))
        assertEquals(false, doc.getBoolean("unusedAppExempt"))
        assertFalse(
            "no instant of the boots is in the file",
            json.contains((t0 - 1.hours).toEpochMilliseconds().toString().take(9)),
        )
    }

    @Test
    fun `below API 30 the unused app restrictions are exported as null`() {
        val doc = JSONObject(ReliabilityExport.toJson(fixture.graph.get<ReliabilityReader>().read(t0), device))

        assertTrue(doc.isNull("unusedAppExempt"))
        assertEquals(0, doc.getInt("unseenBoots"))
    }
}
