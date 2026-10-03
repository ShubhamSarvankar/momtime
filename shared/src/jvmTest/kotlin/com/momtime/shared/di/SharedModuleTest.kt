package com.momtime.shared.di

import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.data.AlarmDeliveryTelemetryRepository
import com.momtime.shared.data.AppSettingsRepository
import com.momtime.shared.data.CaregiverLinkRepository
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.InterruptionBudgetRepository
import com.momtime.shared.data.JvmDatabaseDriverFactory
import com.momtime.shared.data.MomTimeDatabase
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.data.SyncStateRepository
import com.momtime.shared.data.WaterGoalRepository
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.module
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * Verifies the DI graph actually resolves end to end, not just that it compiles — a missing or
 * misconfigured binding here is a real runtime failure mode, distinct from any repository's own
 * unit tests. It also pins what the graph exposes: repositories, never the database.
 */
class SharedModuleTest {
    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    private class CountingFactory(
        private val delegate: DatabaseDriverFactory,
    ) : DatabaseDriverFactory {
        var created = 0

        override fun createDriver(): SqlDriver {
            created++
            return delegate.createDriver()
        }
    }

    private val repositories: List<KClass<*>> =
        listOf(
            PregnancyRepository::class,
            ScheduleTemplateRepository::class,
            OccurrenceRepository::class,
            EventRepository::class,
            AlarmDeliveryTelemetryRepository::class,
            CaregiverLinkRepository::class,
            WaterGoalRepository::class,
            AppSettingsRepository::class,
            InterruptionBudgetRepository::class,
            SyncStateRepository::class,
            WaterGoalRepository::class,
        )

    private fun start(factory: DatabaseDriverFactory) =
        startKoin {
            modules(
                module { single<DatabaseDriverFactory> { factory } },
                clockModule,
                sharedModule(),
            )
        }.koin

    @Test
    fun `every shared binding resolves, backed by a real in-memory database`() {
        val koin = start(JvmDatabaseDriverFactory.inMemory())

        assertNotNull(koin.get<Clock>())
        for (type in repositories) {
            assertNotNull(koin.get<Any>(type), "${type.simpleName} did not resolve")
        }
    }

    // Resolving the database fails. The database (and with it the generated `updateOccurrenceState`
    // query, which would skip the event append) is reachable only through a repository.
    @Test
    fun `the graph does not expose the database`() {
        val koin = start(JvmDatabaseDriverFactory.inMemory())
        for (type in repositories) koin.get<Any>(type)

        assertFailsWith<NoDefinitionFoundException> { koin.get<MomTimeDatabase>() }
        assertFailsWith<NoDefinitionFoundException> { koin.get<SqlDriver>() }
    }

    // A second driver on one database file brings SQLITE_BUSY back (ADR 0036), so every repository
    // shares exactly one.
    @Test
    fun `resolving every repository creates exactly one driver`() {
        val factory = CountingFactory(JvmDatabaseDriverFactory.inMemory())
        val koin = start(factory)
        for (type in repositories) koin.get<Any>(type)

        assertEquals(1, factory.created)
    }

    @Test
    fun `clockModule provides the real system clock, the one allowed Clock-System call site`() {
        val koin = startKoin { modules(clockModule) }.koin
        val clock = koin.get<Clock>()
        assertNotNull(clock)
        assert(clock === Clock.System) { "clockModule must provide exactly Clock.System" }
    }
}
