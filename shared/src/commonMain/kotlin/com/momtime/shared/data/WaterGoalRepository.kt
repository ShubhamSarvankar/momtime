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
        // UPDATE, then a plain INSERT when it changed no row, in one transaction (ADR 0042: no
        // ON CONFLICT upsert below SQLite 3.24). Inside the transaction nothing can insert the row
        // between the two statements, and a plain INSERT fails loudly if that ever stops being true.
        database.transaction {
            val queries = database.waterGoalQueries
            queries.updateWaterGoal(
                daily_goal_ml = goal.dailyGoalMl.toLong(),
                nudge_times_per_day = goal.nudgeTimesPerDay.toLong(),
                pregnancy_id = goal.pregnancyId,
            )
            if (queries.changedRows().executeAsOne() == 0L) {
                queries.insertWaterGoal(
                    pregnancy_id = goal.pregnancyId,
                    daily_goal_ml = goal.dailyGoalMl.toLong(),
                    nudge_times_per_day = goal.nudgeTimesPerDay.toLong(),
                )
            }
        }
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
