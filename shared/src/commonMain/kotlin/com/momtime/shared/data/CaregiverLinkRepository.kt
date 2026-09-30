package com.momtime.shared.data

import com.momtime.shared.domain.CaregiverLink
import com.momtime.shared.domain.NotificationPolicy
import kotlin.time.Instant

interface CaregiverLinkRepository {
    fun insert(link: CaregiverLink)

    /** Hard delete — revocation is immediate and destructive, never a soft flag (ADR 0025). */
    fun deleteById(id: String)

    fun findAll(): List<CaregiverLink>

    fun count(): Long

    fun updatePause(
        id: String,
        pausedUntil: Instant?,
    )

    fun updateNotificationPolicy(
        id: String,
        policy: NotificationPolicy,
    )
}

class SqlDelightCaregiverLinkRepository(
    private val database: MomTimeDatabase,
) : CaregiverLinkRepository {
    override fun insert(link: CaregiverLink) {
        database.caregiverLinkQueries.insertCaregiverLink(
            id = link.id,
            display_name = link.displayName,
            invited_at = link.invitedAt.toDb(),
            scope_weight_visible = link.scopeWeightVisible.toDb(),
            scope_notes_visible = link.scopeNotesVisible.toDb(),
            scope_doctor_instructions_visible = link.scopeDoctorInstructionsVisible.toDb(),
            notification_policy = link.notificationPolicy.name,
            paused_until = link.pausedUntil.toDbOrNull(),
        )
    }

    override fun deleteById(id: String) {
        database.caregiverLinkQueries.deleteCaregiverLink(id)
    }

    override fun findAll(): List<CaregiverLink> =
        database.caregiverLinkQueries
            .selectAllCaregiverLinks()
            .executeAsList()
            .map { it.toDomain() }

    override fun count(): Long = database.caregiverLinkQueries.countCaregiverLinks().executeAsOne()

    override fun updatePause(
        id: String,
        pausedUntil: Instant?,
    ) {
        database.caregiverLinkQueries.updateCaregiverLinkPause(pausedUntil.toDbOrNull(), id)
    }

    override fun updateNotificationPolicy(
        id: String,
        policy: NotificationPolicy,
    ) {
        database.caregiverLinkQueries.updateCaregiverLinkNotificationPolicy(policy.name, id)
    }

    private fun com.momtime.shared.data.Caregiver_link.toDomain() =
        CaregiverLink(
            id = id,
            displayName = display_name,
            invitedAt = invited_at.toInstant(),
            scopeWeightVisible = scope_weight_visible.toBoolean(),
            scopeNotesVisible = scope_notes_visible.toBoolean(),
            scopeDoctorInstructionsVisible = scope_doctor_instructions_visible.toBoolean(),
            notificationPolicy = NotificationPolicy.valueOf(notification_policy),
            pausedUntil = paused_until.toInstantOrNull(),
        )
}
