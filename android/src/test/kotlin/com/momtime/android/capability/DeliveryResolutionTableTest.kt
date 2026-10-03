package com.momtime.android.capability

import com.momtime.shared.domain.DeliveryCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivery resolution for every combination of its six inputs (ADR 0050). The expected result is
 * data in `delivery-resolution-table.csv`, written once by a separate script from the ADR's rules and
 * checked in, not computed here: a test that computed its answer with the code's own rules would pass
 * whatever the rules were. The test enumerates all 64 combinations and fails if one is missing from the
 * table, or listed twice, or if the table holds a row no combination produces, so an input added to
 * `CapabilityInputs` cannot go unhandled silently.
 */
class DeliveryResolutionTableTest {
    private data class Row(
        val inputs: CapabilityInputs,
        val expected: DeliveryResolution,
        val line: String,
    )

    private fun String.flag() = this == "1"

    private fun loadTable(): List<Row> {
        val text =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("delivery-resolution-table.csv")) {
                "delivery-resolution-table.csv is not on the test classpath"
            }.bufferedReader().readText()
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        check(lines.first().startsWith("exact,")) { "the table header is missing" }
        return lines.drop(1).map { line ->
            val c = line.split(",")
            check(c.size == 13) { "a row must have 13 columns: $line" }
            Row(
                inputs = CapabilityInputs(c[0].flag(), c[1].flag(), c[2].flag(), c[3].flag(), c[4].flag(), c[5].flag()),
                expected =
                    DeliveryResolution(
                        tier = ResolvedTier.valueOf(c[6]),
                        mechanism = DeliveryMechanism.valueOf(c[7]),
                        fullScreenIntent = c[8].flag(),
                        headsUp = c[9].flag(),
                        overlayAvailable = c[10].flag(),
                        audioOnly = c[11].flag(),
                        undeliverable = c[12].flag(),
                    ),
                line = line,
            )
        }
    }

    private val every: List<CapabilityInputs> =
        listOf(false, true).flatMap { exact ->
            listOf(false, true).flatMap { fsi ->
                listOf(false, true).flatMap { notifications ->
                    listOf(false, true).flatMap { battery ->
                        listOf(false, true).flatMap { overlay ->
                            listOf(false, true).map { critical ->
                                CapabilityInputs(exact, fsi, notifications, battery, overlay, critical)
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `there are 64 combinations of the six inputs`() {
        assertEquals(64, every.size)
        assertEquals(64, every.toSet().size)
    }

    @Test
    fun `the table covers every combination exactly once and nothing else`() {
        val rows = loadTable()
        val byInputs = rows.groupBy { it.inputs }

        val missing = every.filter { it !in byInputs }
        assertTrue("combinations missing from the table (${missing.size}): $missing", missing.isEmpty())
        val duplicated = byInputs.filter { it.value.size > 1 }.keys
        assertTrue("combinations listed more than once: $duplicated", duplicated.isEmpty())
        val unknown = byInputs.keys - every.toSet()
        assertTrue("rows no combination produces: $unknown", unknown.isEmpty())
        assertEquals(64, rows.size)
    }

    @Test
    fun `every combination resolves to what the table says`() {
        val wrong =
            loadTable().mapNotNull { row ->
                val actual = resolveDelivery(row.inputs)
                if (actual == row.expected) null else "${row.line}\n    resolved to $actual"
            }
        assertTrue(
            "${wrong.size} of 64 combinations resolve differently from the table:\n${wrong.joinToString("\n")}",
            wrong.isEmpty(),
        )
    }

    @Test
    fun `the table is not vacuous`() {
        val tiers = loadTable().map { it.expected.tier }.toSet()
        assertEquals("the table must exercise every tier", ResolvedTier.entries.toSet(), tiers)
        val rows = loadTable()
        assertTrue(rows.any { it.expected.undeliverable })
        assertTrue(rows.any { it.expected.audioOnly })
        assertTrue(rows.any { it.expected.overlayAvailable })
        assertTrue(rows.any { it.expected.fullScreenIntent && it.expected.headsUp })
        // A blocked Critical channel keeps Tier 3 out even with everything else granted.
        val blocked = rows.first { it.inputs == CapabilityInputs(true, true, true, true, true, false) }
        assertEquals(ResolvedTier.EXACT, blocked.expected.tier)
    }

    @Test
    fun `the android tier maps to the shared capability, one to one`() {
        assertEquals(DeliveryCapability.TIER_3, ResolvedTier.FULL.toDeliveryCapability())
        assertEquals(DeliveryCapability.TIER_2, ResolvedTier.EXACT.toDeliveryCapability())
        assertEquals(DeliveryCapability.TIER_1, ResolvedTier.INEXACT.toDeliveryCapability())
        assertEquals(
            "the mapping must reach every shared tier exactly once",
            DeliveryCapability.entries.toSet(),
            ResolvedTier.entries.map { it.toDeliveryCapability() }.toSet(),
        )
        assertEquals(DeliveryCapability.entries.size, ResolvedTier.entries.size)
    }

    @Test
    fun `the resolution carries the mapped capability`() {
        val full = resolveDelivery(CapabilityInputs(true, true, true, true, true, true))
        assertEquals(DeliveryCapability.TIER_3, full.capability)
        val inexact = resolveDelivery(CapabilityInputs(false, true, true, true, true, true))
        assertEquals(DeliveryCapability.TIER_1, inexact.capability)
        assertEquals(DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE, inexact.mechanism)
    }
}
