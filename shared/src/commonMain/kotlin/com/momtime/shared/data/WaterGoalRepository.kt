package com.momtime.shared.data

import com.momtime.shared.domain.WaterGoal

interface WaterGoalRepository {
    fun upsert(goal: WaterGoal)

    fun findForPregnancy(pregnancyId: String): WaterGoal?
}

class SqlDelightWaterGoalRepository(
    private val database: MomTimeDatabase,
) : WaterGoalRepository {
    override fun upsert(goal: WaterGoal) {
        database.waterGoalQueries.upsertWaterGoal(
            pregnancy_id = goal.pregnancyId,
            daily_goal_ml = goal.dailyGoalMl.toLong(),
            nudge_times_per_day = goal.nudgeTimesPerDay.toLong(),
        )
    }

    override fun findForPregnancy(pregnancyId: String): WaterGoal? =
        database.waterGoalQueries.selectWaterGoal(pregnancyId).executeAsOneOrNull()?.let {
            WaterGoal(
                pregnancyId = it.pregnancy_id,
                dailyGoalMl = it.daily_goal_ml.toInt(),
                nudgeTimesPerDay = it.nudge_times_per_day.toInt(),
            )
        }
}
