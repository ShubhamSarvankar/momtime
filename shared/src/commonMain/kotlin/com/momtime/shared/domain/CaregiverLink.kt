package com.momtime.shared.domain

import kotlin.time.Instant

/**
 * Read-only, always (ADR 0025) — there is no caregiver write path anywhere. Title, scheduled
 * time, and state are always visible by default with no toggle (ARCHITECTURE.md section 7);
 * only weight/notes/doctorInstructions are individually scoped.
 */
data class CaregiverLink(
    val id: String,
    val displayName: String,
    val invitedAt: Instant,
    val scopeWeightVisible: Boolean,
    val scopeNotesVisible: Boolean,
    val scopeDoctorInstructionsVisible: Boolean,
    val notificationPolicy: NotificationPolicy,
    val pausedUntil: Instant?,
) {
    fun isPausedAt(now: Instant): Boolean = pausedUntil != null && now < pausedUntil
}
