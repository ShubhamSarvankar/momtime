package com.momtime.shared.di

import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.JvmDatabaseDriverFactory
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * Verifies the DI graph actually resolves end to end, not just that it compiles — a missing or
 * misconfigured binding here is a real runtime failure mode, distinct from any repository's own
 * unit tests.
 */
class SharedModuleTest {
    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `every shared binding resolves, backed by a real in-memory database`() {
        val koin =
            startKoin {
                modules(
                    module { single<DatabaseDriverFactory> { JvmDatabaseDriverFactory.inMemory() } },
                    clockModule,
                    sharedModule,
                )
            }.koin

        assertNotNull(koin.get<Clock>())
        assertNotNull(koin.get<PregnancyRepository>())
        assertNotNull(koin.get<ScheduleTemplateRepository>())
        assertNotNull(koin.get<OccurrenceRepository>())
        assertNotNull(koin.get<EventRepository>())
    }

    @Test
    fun `clockModule provides the real system clock, the one allowed Clock-System call site`() {
        val koin = startKoin { modules(clockModule) }.koin
        val clock = koin.get<Clock>()
        assertNotNull(clock)
        assert(clock === Clock.System) { "clockModule must provide exactly Clock.System" }
    }
}
