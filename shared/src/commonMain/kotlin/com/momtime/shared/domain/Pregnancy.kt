package com.momtime.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

data class Pregnancy(
    val id: String,
    val phase: PregnancyPhase,
    val createdAt: Instant,
    val phaseChangedAt: Instant,
)

/**
 * Due date is the single source of truth for gestational week (ARCHITECTURE.md section 3.5).
 * Edits are appended, never overwritten, so a revision can be read either as "current" (latest)
 * or "as of" a past instant — see ARCHITECTURE.md section 3.5's asOf requirement and golden
 * scenario M1's test.
 */
data class DueDateRevision(
    val id: String,
    val pregnancyId: String,
    val dueDate: LocalDate,
    val recordedAt: Instant,
)
