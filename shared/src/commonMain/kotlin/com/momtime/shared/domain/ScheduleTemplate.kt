package com.momtime.shared.domain

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * What the user or her doctor configured (ARCHITECTURE.md section 3.1). taskType carries no
 * behaviour (ADR 0005) — all escalation/budget/grace behaviour reads criticality.
 */
data class ScheduleTemplate(
    val id: String,
    val pregnancyId: String,
    val title: String,
    val notes: String?,
    val taskType: TaskType,
    val criticality: Criticality,
    val timeOfDay: LocalTime,
    val timeZoneId: TimeZone,
    val recurrence: Recurrence,
    val mission: MissionConfig,
    val nutritionTags: Set<NutritionTag>,
    val dosage: String?,
    val doctorInstructions: String?,
    val inventoryCount: Int?,
    val refillThresholdDays: Int?,
    val active: Boolean,
)
