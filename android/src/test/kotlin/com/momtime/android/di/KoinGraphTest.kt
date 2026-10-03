package com.momtime.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.data.AlarmDeliveryTelemetryRepository
import com.momtime.shared.data.AppSettingsRepository
import com.momtime.shared.data.CaregiverLinkRepository
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.InterruptionBudgetRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.data.SyncStateRepository
import com.momtime.shared.data.WaterGoalRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.KoinApplication
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID
import kotlin.reflect.KClass

/**
 * What the Koin graph exposes. In package `di` because it names the database type to prove it cannot
 * be resolved, which the structural check allows only here (Phase 2: the Koin graph exposes
 * repositories only).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class KoinGraphTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val drivers = mutableListOf<SqlDriver>()
    private var application: KoinApplication? = null

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
        )

    private class Counting(
        private val delegate: DatabaseDriverFactory,
        private val sink: MutableList<SqlDriver>,
    ) : DatabaseDriverFactory {
        override fun createDriver(): SqlDriver = delegate.createDriver().also { sink.add(it) }
    }

    private fun start(): KoinApplication {
        val name = "k-${UUID.randomUUID().toString().take(8)}.db"
        context.getDatabasePath(name).parentFile?.mkdirs()
        val factory = Counting(AndroidDatabaseDriverFactory(context, name), drivers)
        return koinApplication { modules(momTimeModules(context, factory)) }.also { application = it }
    }

    @After
    fun tearDown() {
        application?.close()
        drivers.forEach { runCatching { it.close() } }
    }

    @Test
    fun `every repository resolves`() {
        val koin = start().koin
        for (type in repositories) {
            assertNotNull("${type.simpleName} did not resolve", koin.get<Any>(type))
        }
    }

    // The database, with the generated query that would skip the event append, is reachable only
    // through a repository. Resolving it fails; so does resolving the driver itself.
    @Test
    fun `resolving the database fails`() {
        val koin = start().koin
        for (type in repositories) koin.get<Any>(type)

        for (hidden in listOf(Class.forName("com.momtime.shared.data.MomTimeDatabase").kotlin, SqlDriver::class)) {
            try {
                koin.get<Any>(hidden)
                fail("${hidden.simpleName} resolved from the graph")
            } catch (expected: NoDefinitionFoundException) {
                // The graph does not hold it.
            }
        }
    }

    // A second driver on one file brings SQLITE_BUSY back (ADR 0036), so all repositories share one.
    @Test
    fun `resolving every repository creates exactly one driver`() {
        val koin = start().koin
        for (type in repositories) koin.get<Any>(type)

        assertEquals("a second driver was created", 1, drivers.size)
    }

    @Test
    fun `the driver factory is a single and the repositories are singles`() {
        val koin = start().koin
        assertSame(koin.get<DatabaseDriverFactory>(), koin.get<DatabaseDriverFactory>())
        assertSame(koin.get<OccurrenceRepository>(), koin.get<OccurrenceRepository>())
    }
}
