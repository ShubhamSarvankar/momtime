package com.momtime.shared.domain

import kotlin.time.Instant

/**
 * The engine emits the full escalation ladder as a declarative, ordered list (ADR 0010). It
 * does not schedule, take callbacks, or know what a platform will do with the list. Carries no
 * slot of its own — alarmSlot lives on Occurrence, per ADR 0031's narrowing note and
 * ARCHITECTURE.md sections 4.1/5.4.
 */
data class EscalationRung(
    val instant: Instant,
    val channel: Channel,
)
