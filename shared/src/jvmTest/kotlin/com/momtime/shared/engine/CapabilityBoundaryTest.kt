package com.momtime.shared.engine

import com.momtime.shared.domain.AlarmDeliveryTelemetry
import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.EscalationRung
import java.lang.reflect.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden scenario 3, the part whose subject is shared code (CLAUDE.md invariant 5, ADR 0019).
 * Platform capability differences live only in delivery and presentation. A tier downgrade after
 * exact-alarm permission is revoked changes which mechanism realises a rung, never the ladder, so
 * DeliveryCapability must not appear anywhere in the escalation engine's API or in the rung types.
 * The downgrade itself is Android and is a Phase 2 exit criterion.
 *
 * This is a structural check over the compiled types, so it fails if a capability parameter, field
 * or return type is ever added to the engine. It replaces an earlier test that only asserted a
 * ladder had four rungs and could not fail on a leak.
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

    @Test
    fun `no escalation engine or rung type mentions DeliveryCapability`() {
        val leaks = engineTypes.flatMap(::capabilityMentions)
        assertEquals(emptyList(), leaks, "capability leaked into the shared escalation engine")
    }

    // A check that scans nothing, or cannot see a capability, would pass forever. The scan must
    // cover real members, and must demonstrably flag a type that does carry a capability.
    @Test
    fun `the scan is not vacuous`() {
        val scanned = engineTypes.sumOf { it.declaredMethods.size + it.declaredFields.size }
        assertTrue(scanned > 30, "the scan saw only $scanned members")
        assertTrue(
            capabilityMentions(AlarmDeliveryTelemetry::class.java).isNotEmpty(),
            "positive control: AlarmDeliveryTelemetry holds a DeliveryCapability and must be flagged",
        )
    }
}
