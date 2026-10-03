package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.ScheduleTemplate
import java.io.File
import java.lang.reflect.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * CLAUDE.md invariant 5, ADR 0019, ADR 0048. Platform capability differences live only in delivery
 * and presentation. A tier downgrade after exact-alarm permission is revoked changes which
 * mechanism realises a rung, never the ladder, so DeliveryCapability must not appear in the
 * escalation engine's API, in the rung types, in the types that are stored (the event, its payloads,
 * the occurrence, the template), or as a column in the shared schema.
 *
 * Each is a structural check, so it fails if a capability parameter, field, return type or column is
 * ever added. The downgrade itself is Android (Phase 2, golden scenario 3).
 *
 * Phase 1 held a table of device state and the resolved tier in the shared schema, accepted by an ADR,
 * and every check passed: this file did not look at stored types or at the schema. It does now.
 */
class CapabilityBoundaryTest {
    private val engineTypes: List<Class<*>> =
        listOf(
            EscalationLadder::class.java,
            EscalationPolicy::class.java,
            NextRungResolver::class.java,
            NextRungResolver.PendingLadder::class.java,
            Reconcile::class.java,
            OccurrenceMaterialiser::class.java,
            SnoozePolicy::class.java,
            EventLogReduction::class.java,
            EventLogReduction.AdherenceFigures::class.java,
            RecurrenceExpander::class.java,
            EscalationRung::class.java,
            Channel::class.java,
        )

    // The types that are written to the shared database or into the log.
    private val storedTypes: List<Class<*>> =
        listOf(
            Event::class.java,
            EventPayload::class.java,
            EventPayload.None::class.java,
            EventPayload.Snooze::class.java,
            EventPayload.MissionResult::class.java,
            EventPayload.Water::class.java,
            EventPayload.Weight::class.java,
            EventPayload.CaregiverReference::class.java,
            EventPayload.Canary::class.java,
            Occurrence::class.java,
            ScheduleTemplate::class.java,
        )

    private fun mentionsCapability(type: Type) = type.typeName.contains("DeliveryCapability")

    private fun capabilityMentions(clazz: Class<*>): List<String> {
        val found = mutableListOf<String>()
        clazz.declaredMethods.forEach { m ->
            (m.genericParameterTypes + m.genericReturnType).filter(::mentionsCapability).forEach {
                found += "${clazz.simpleName}.${m.name} mentions ${it.typeName}"
            }
        }
        clazz.declaredConstructors.forEach { c ->
            c.genericParameterTypes.filter(::mentionsCapability).forEach {
                found += "${clazz.simpleName} constructor mentions ${it.typeName}"
            }
        }
        clazz.declaredFields.filter { mentionsCapability(it.genericType) }.forEach {
            found += "${clazz.simpleName}.${it.name} is ${it.genericType.typeName}"
        }
        return found
    }

    // Column names that describe the delivery tier or the state of the device. None belongs in the
    // shared schema. The current schema is the .sq files; the migrations are history and are not scanned.
    private val deviceColumnPattern =
        Regex(
            """\b(resolved_tier|tier|screen_on|audio_focus\w*|battery\w*|doze\w*|boot_count|exact_allowed)\b""",
            RegexOption.IGNORE_CASE,
        )

    private fun deviceColumns(sql: String): List<String> =
        sql.lines().mapIndexedNotNull { index, line ->
            val code = line.substringBefore("--")
            if (deviceColumnPattern.containsMatchIn(code)) "line ${index + 1}: ${line.trim()}" else null
        }

    private val schemaFiles: List<File> =
        File("src/commonMain/sqldelight/com/momtime/shared/data")
            .listFiles { f ->
                f.extension == "sq"
            }.orEmpty()
            .toList()

    @Test
    fun `no escalation engine or rung type mentions DeliveryCapability`() {
        val leaks = engineTypes.flatMap(::capabilityMentions)
        assertEquals(emptyList(), leaks, "capability leaked into the shared escalation engine")
    }

    @Test
    fun `no stored type mentions DeliveryCapability`() {
        val leaks = storedTypes.flatMap(::capabilityMentions)
        assertEquals(emptyList(), leaks, "capability leaked into a type that is written to the log or the schema")
    }

    @Test
    fun `no column in the shared schema describes the tier or the state of the device`() {
        val leaks = schemaFiles.flatMap { file -> deviceColumns(file.readText()).map { "${file.name}: $it" } }
        assertEquals(emptyList(), leaks, "a delivery tier or device state column is in the shared schema")
    }

    // A check that scans nothing, or cannot see a capability, would pass forever. Each scan must cover
    // real members, and must demonstrably flag something that does carry a capability.
    @Test
    fun `the scans are not vacuous`() {
        val scanned = engineTypes.sumOf { it.declaredMethods.size + it.declaredFields.size }
        assertTrue(scanned > 30, "the engine scan saw only $scanned members")
        val storedScanned = storedTypes.sumOf { it.declaredMethods.size + it.declaredFields.size }
        assertTrue(storedScanned > 30, "the stored type scan saw only $storedScanned members")
        assertTrue(schemaFiles.size >= 10, "the schema scan found only ${schemaFiles.size} .sq files")
        assertTrue(schemaFiles.any { it.name == "Event.sq" }, "the schema scan did not find Event.sq")
    }

    // Positive controls: types and columns that plant a capability, which the scans must flag.
    private data class PlantedCapabilityRung(
        val instant: Instant,
        val tier: DeliveryCapability,
    )

    @Test
    fun `the type scan flags a planted capability reference`() {
        assertTrue(
            capabilityMentions(PlantedCapabilityRung::class.java).isNotEmpty(),
            "positive control: a rung that holds a DeliveryCapability must be flagged",
        )
    }

    @Test
    fun `the column scan flags a planted tier column and device state columns`() {
        val planted =
            """
            CREATE TABLE planted (
                resolved_tier TEXT,
                screen_on INTEGER,
                audio_focus_obtained INTEGER,
                battery_pct INTEGER,
                doze_state TEXT,
                boot_count INTEGER
            );
            """.trimIndent()
        assertEquals(6, deviceColumns(planted).size, "positive control: each planted column must be flagged")
        assertEquals(emptyList(), deviceColumns("CREATE TABLE ok (id TEXT, snooze_number INTEGER);"))
    }
}
