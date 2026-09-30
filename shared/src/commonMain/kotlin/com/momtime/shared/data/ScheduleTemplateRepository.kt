package com.momtime.shared.data

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

interface ScheduleTemplateRepository {
    fun insert(template: ScheduleTemplate)

    fun findById(id: String): ScheduleTemplate?

    fun findActiveForPregnancy(pregnancyId: String): List<ScheduleTemplate>

    fun setActive(
        id: String,
        active: Boolean,
    )
}

class SqlDelightScheduleTemplateRepository(
    private val database: MomTimeDatabase,
) : ScheduleTemplateRepository {
    override fun insert(template: ScheduleTemplate) {
        val recurrenceColumns = template.recurrence.toColumns()
        val missionColumns = template.mission.toColumns()
        database.scheduleTemplateQueries.insertScheduleTemplate(
            id = template.id,
            pregnancy_id = template.pregnancyId,
            title = template.title,
            notes = template.notes,
            task_type = template.taskType.name,
            criticality = template.criticality.name,
            time_of_day = template.timeOfDay.toString(),
            time_zone_id = template.timeZoneId.toDb(),
            recurrence_type = recurrenceColumns.type,
            recurrence_days_of_week = recurrenceColumns.daysOfWeek,
            recurrence_n = recurrenceColumns.n,
            recurrence_anchor_date = recurrenceColumns.anchorDate,
            mission_type = missionColumns.type,
            mission_barcode_payload = missionColumns.barcodePayload,
            mission_photo_reference_hash = missionColumns.photoReferenceHash,
            dosage = template.dosage,
            doctor_instructions = template.doctorInstructions,
            inventory_count = template.inventoryCount?.toLong(),
            refill_threshold_days = template.refillThresholdDays?.toLong(),
            active = template.active.toDb(),
        )
        template.nutritionTags.forEach { tag ->
            database.scheduleTemplateQueries.insertScheduleTemplateNutritionTag(template.id, tag.name)
        }
    }

    override fun findById(id: String): ScheduleTemplate? =
        database.scheduleTemplateQueries
            .selectScheduleTemplateById(id)
            .executeAsOneOrNull()
            ?.let { toDomain(it) }

    override fun findActiveForPregnancy(pregnancyId: String): List<ScheduleTemplate> =
        database.scheduleTemplateQueries.selectActiveScheduleTemplatesForPregnancy(pregnancyId).executeAsList().map {
            toDomain(it)
        }

    override fun setActive(
        id: String,
        active: Boolean,
    ) {
        database.scheduleTemplateQueries.updateScheduleTemplateActive(active.toDb(), id)
    }

    private fun toDomain(row: com.momtime.shared.data.Schedule_template): ScheduleTemplate {
        val tags =
            database.scheduleTemplateQueries
                .selectNutritionTagsForTemplate(row.id)
                .executeAsList()
                .map { NutritionTag.valueOf(it) }
                .toSet()
        return ScheduleTemplate(
            id = row.id,
            pregnancyId = row.pregnancy_id,
            title = row.title,
            notes = row.notes,
            taskType = TaskType.valueOf(row.task_type),
            criticality = Criticality.valueOf(row.criticality),
            timeOfDay = LocalTime.parse(row.time_of_day),
            timeZoneId = row.time_zone_id.toTimeZone(),
            recurrence = row.toRecurrence(),
            mission = row.toMissionConfig(),
            nutritionTags = tags,
            dosage = row.dosage,
            doctorInstructions = row.doctor_instructions,
            inventoryCount = row.inventory_count?.toInt(),
            refillThresholdDays = row.refill_threshold_days?.toInt(),
            active = row.active.toBoolean(),
        )
    }

    private data class RecurrenceColumns(
        val type: String,
        val daysOfWeek: String?,
        val n: Long?,
        val anchorDate: String?,
    )

    private fun Recurrence.toColumns(): RecurrenceColumns =
        when (this) {
            is Recurrence.Daily -> RecurrenceColumns("DAILY", null, null, null)
            is Recurrence.Weekly ->
                RecurrenceColumns("WEEKLY", daysOfWeek.joinToString(",") { it.name }, null, null)
            is Recurrence.EveryNDays ->
                RecurrenceColumns("EVERY_N_DAYS", null, n.toLong(), anchorDate.toDb())
        }

    private fun com.momtime.shared.data.Schedule_template.toRecurrence(): Recurrence =
        when (recurrence_type) {
            "DAILY" -> Recurrence.Daily
            "WEEKLY" ->
                Recurrence.Weekly(
                    recurrence_days_of_week!!.split(",").map { DayOfWeek.valueOf(it) }.toSet(),
                )
            "EVERY_N_DAYS" ->
                Recurrence.EveryNDays(recurrence_n!!.toInt(), recurrence_anchor_date!!.toLocalDate())
            else -> error("Unknown recurrence_type: $recurrence_type")
        }

    private data class MissionColumns(
        val type: String,
        val barcodePayload: String?,
        val photoReferenceHash: String?,
    )

    private fun MissionConfig.toColumns(): MissionColumns =
        when (this) {
            is MissionConfig.None -> MissionColumns("NONE", null, null)
            is MissionConfig.Barcode -> MissionColumns("BARCODE", expectedPayload, null)
            is MissionConfig.PhotoMatch -> MissionColumns("PHOTO_MATCH", null, referenceHash)
        }

    private fun com.momtime.shared.data.Schedule_template.toMissionConfig(): MissionConfig =
        when (mission_type) {
            "NONE" -> MissionConfig.None
            "BARCODE" -> MissionConfig.Barcode(mission_barcode_payload!!)
            "PHOTO_MATCH" -> MissionConfig.PhotoMatch(mission_photo_reference_hash!!)
            else -> error("Unknown mission_type: $mission_type")
        }
}
