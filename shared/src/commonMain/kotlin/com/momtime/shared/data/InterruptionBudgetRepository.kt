package com.momtime.shared.data

import com.momtime.shared.domain.InterruptionBudget
import kotlinx.datetime.LocalDate

interface InterruptionBudgetRepository {
    fun ensureSeeded(today: LocalDate)

    fun current(): InterruptionBudget

    fun reset(today: LocalDate)

    fun increment()
}

class SqlDelightInterruptionBudgetRepository(
    private val database: MomTimeDatabase,
) : InterruptionBudgetRepository {
    override fun ensureSeeded(today: LocalDate) {
        database.interruptionBudgetQueries.seedInterruptionBudget(today.toDb())
    }

    override fun current(): InterruptionBudget {
        val row = database.interruptionBudgetQueries.selectInterruptionBudget().executeAsOne()
        return InterruptionBudget(budgetDate = row.budget_date.toLocalDate(), ringCount = row.ring_count.toInt())
    }

    override fun reset(today: LocalDate) {
        database.interruptionBudgetQueries.resetInterruptionBudget(today.toDb())
    }

    override fun increment() {
        database.interruptionBudgetQueries.incrementInterruptionBudget()
    }
}
