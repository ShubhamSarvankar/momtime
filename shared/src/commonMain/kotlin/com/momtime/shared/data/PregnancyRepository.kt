package com.momtime.shared.data

import com.momtime.shared.domain.DueDateRevision
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import kotlin.time.Instant

interface PregnancyRepository {
    fun insert(pregnancy: Pregnancy)

    fun findById(id: String): Pregnancy?

    fun updatePhase(
        id: String,
        phase: PregnancyPhase,
        changedAt: Instant,
    )

    fun insertDueDateRevision(revision: DueDateRevision)

    /** Current view — the latest revision. Never use for a historical/past-dated view. */
    fun latestDueDateRevision(pregnancyId: String): DueDateRevision?

    /**
     * The revision in effect at [asOf] — every historical/report view must call this, not
     * [latestDueDateRevision], so a due-date revision cannot silently rewrite a past report
     * (ARCHITECTURE.md section 3.5).
     */
    fun dueDateRevisionAsOf(
        pregnancyId: String,
        asOf: Instant,
    ): DueDateRevision?
}

class SqlDelightPregnancyRepository(
    private val database: MomTimeDatabase,
) : PregnancyRepository {
    override fun insert(pregnancy: Pregnancy) {
        database.pregnancyQueries.insertPregnancy(
            id = pregnancy.id,
            phase = pregnancy.phase.name,
            created_at = pregnancy.createdAt.toDb(),
            phase_changed_at = pregnancy.phaseChangedAt.toDb(),
        )
    }

    override fun findById(id: String): Pregnancy? =
        database.pregnancyQueries
            .selectPregnancyById(id)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun updatePhase(
        id: String,
        phase: PregnancyPhase,
        changedAt: Instant,
    ) {
        database.pregnancyQueries.updatePregnancyPhase(
            phase = phase.name,
            phase_changed_at = changedAt.toDb(),
            id = id,
        )
    }

    override fun insertDueDateRevision(revision: DueDateRevision) {
        database.pregnancyQueries.insertDueDateRevision(
            id = revision.id,
            pregnancy_id = revision.pregnancyId,
            due_date = revision.dueDate.toDb(),
            recorded_at = revision.recordedAt.toDb(),
        )
    }

    override fun latestDueDateRevision(pregnancyId: String): DueDateRevision? =
        database.pregnancyQueries
            .selectLatestDueDateRevision(pregnancyId)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun dueDateRevisionAsOf(
        pregnancyId: String,
        asOf: Instant,
    ): DueDateRevision? =
        database.pregnancyQueries
            .selectDueDateRevisionAsOf(pregnancyId, asOf.toDb())
            .executeAsOneOrNull()
            ?.toDomain()

    private fun com.momtime.shared.data.Pregnancy.toDomain() =
        Pregnancy(
            id = id,
            phase = PregnancyPhase.valueOf(phase),
            createdAt = created_at.toInstant(),
            phaseChangedAt = phase_changed_at.toInstant(),
        )

    private fun com.momtime.shared.data.Due_date_revision.toDomain() =
        DueDateRevision(
            id = id,
            pregnancyId = pregnancy_id,
            dueDate = due_date.toLocalDate(),
            recordedAt = recorded_at.toInstant(),
        )
}
