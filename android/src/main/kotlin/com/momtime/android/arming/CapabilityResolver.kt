package com.momtime.android.arming

import com.momtime.android.capability.CapabilityInputs
import com.momtime.android.capability.DeliveryResolution
import com.momtime.android.capability.PlatformCapabilityReader
import com.momtime.android.capability.resolveDelivery

/**
 * The current delivery resolution (ADR 0050). [resolve] reads the platform and resolves; [current] is what
 * the last call decided, which is what the scheduler arms through. Only "ensure armed" calls [resolve] at
 * the start of a pass, so one pass reads the platform once and arms through what it read.
 */
class CapabilityResolver(
    private val reader: PlatformCapabilityReader,
) {
    private var inputs: CapabilityInputs = reader.read()

    @Volatile
    var current: DeliveryResolution = resolveDelivery(inputs)
        private set

    @Synchronized
    fun resolve(): DeliveryResolution {
        inputs = reader.read()
        return resolveDelivery(inputs).also { current = it }
    }

    /**
     * The platform refused an exact alarm although it said exact alarms were allowed. A refusal is positive
     * evidence, and reliability wins: resolve again as if exact capability were absent, so the alarm is armed
     * inexactly rather than not at all. The next [resolve] reads the platform afresh.
     */
    @Synchronized
    fun refuseExact(): DeliveryResolution {
        inputs = inputs.copy(exactAlarm = false)
        return resolveDelivery(inputs).also { current = it }
    }
}
