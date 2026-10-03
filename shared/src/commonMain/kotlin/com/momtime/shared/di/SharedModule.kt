package com.momtime.shared.di

import com.momtime.shared.data.AppSettingsRepository
import com.momtime.shared.data.CaregiverLinkRepository
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.InterruptionBudgetRepository
import com.momtime.shared.data.MomTimeDatabase
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.data.SqlDelightAppSettingsRepository
import com.momtime.shared.data.SqlDelightCaregiverLinkRepository
import com.momtime.shared.data.SqlDelightEventRepository
import com.momtime.shared.data.SqlDelightInterruptionBudgetRepository
import com.momtime.shared.data.SqlDelightOccurrenceRepository
import com.momtime.shared.data.SqlDelightPregnancyRepository
import com.momtime.shared.data.SqlDelightScheduleTemplateRepository
import com.momtime.shared.data.SqlDelightSyncStateRepository
import com.momtime.shared.data.SqlDelightWaterGoalRepository
import com.momtime.shared.data.SyncStateRepository
import com.momtime.shared.data.WaterGoalRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Holds the one [MomTimeDatabase], built on first use from the [DatabaseDriverFactory]. It is
 * `internal`, so no other module can name it, and it is the only place a driver is created: a
 * second driver on the same file brings SQLITE_BUSY back (ADR 0036). The database is not a Koin
 * definition of its own, so `get<MomTimeDatabase>()` fails: a caller can reach repositories and
 * nothing below them, which keeps the generated `updateOccurrenceState` query out of reach
 * (IMPLEMENTATION_PLAN.md, Phase 2: the Koin graph exposes repositories only).
 */
internal class DatabaseHolder(
    factory: DatabaseDriverFactory,
) {
    val database: MomTimeDatabase by lazy { MomTimeDatabase(factory.createDriver()) }
}

/**
 * The repositories, all over one database. Needs a [DatabaseDriverFactory] from the platform
 * module. A function and not a value, so each Koin application gets its own holder.
 */
fun sharedModule(): Module =
    module {
        single { DatabaseHolder(get()) }
        single<PregnancyRepository> { SqlDelightPregnancyRepository(get<DatabaseHolder>().database) }
        single<ScheduleTemplateRepository> { SqlDelightScheduleTemplateRepository(get<DatabaseHolder>().database) }
        single<OccurrenceRepository> { SqlDelightOccurrenceRepository(get<DatabaseHolder>().database) }
        single<EventRepository> { SqlDelightEventRepository(get<DatabaseHolder>().database) }
        single<CaregiverLinkRepository> { SqlDelightCaregiverLinkRepository(get<DatabaseHolder>().database) }
        single<WaterGoalRepository> { SqlDelightWaterGoalRepository(get<DatabaseHolder>().database) }
        single<AppSettingsRepository> { SqlDelightAppSettingsRepository(get<DatabaseHolder>().database) }
        single<InterruptionBudgetRepository> {
            SqlDelightInterruptionBudgetRepository(get<DatabaseHolder>().database)
        }
        single<SyncStateRepository> { SqlDelightSyncStateRepository(get<DatabaseHolder>().database) }
    }
