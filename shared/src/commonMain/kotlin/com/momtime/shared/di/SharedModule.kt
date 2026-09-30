package com.momtime.shared.di

import com.momtime.shared.data.AlarmDeliveryTelemetryRepository
import com.momtime.shared.data.AppSettingsRepository
import com.momtime.shared.data.CaregiverLinkRepository
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.InterruptionBudgetRepository
import com.momtime.shared.data.MomTimeDatabase
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.data.SqlDelightAlarmDeliveryTelemetryRepository
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
import org.koin.dsl.module

val sharedModule =
    module {
        single { MomTimeDatabase(get<DatabaseDriverFactory>().createDriver()) }
        single<PregnancyRepository> { SqlDelightPregnancyRepository(get()) }
        single<ScheduleTemplateRepository> { SqlDelightScheduleTemplateRepository(get()) }
        single<OccurrenceRepository> { SqlDelightOccurrenceRepository(get()) }
        single<EventRepository> { SqlDelightEventRepository(get()) }
        single<AlarmDeliveryTelemetryRepository> { SqlDelightAlarmDeliveryTelemetryRepository(get()) }
        single<CaregiverLinkRepository> { SqlDelightCaregiverLinkRepository(get()) }
        single<WaterGoalRepository> { SqlDelightWaterGoalRepository(get()) }
        single<AppSettingsRepository> { SqlDelightAppSettingsRepository(get()) }
        single<InterruptionBudgetRepository> { SqlDelightInterruptionBudgetRepository(get()) }
        single<SyncStateRepository> { SqlDelightSyncStateRepository(get()) }
    }
