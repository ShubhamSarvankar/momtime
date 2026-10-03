package com.momtime.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.store.FireTelemetryRepository
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
 * What the Koin graph exposes. In package `di` because it names the database types to prove they
 * cannot be resolved, which the structural check allows only here (Phase 2: the Koin graph exposes
 * repositories only).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class KoinGraphTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val sharedDrivers = mutableListOf<SqlDriver>()
    private val storeDrivers = mutableListOf<SqlDriver>()
    private var application: KoinApplication? = null

    private val sharedRepositories: List<KClass<*>> =
        listOf(
            PregnancyRepository::class,
            ScheduleTemplateRepository::class,
            OccurrenceRepository::class,
            EventRepository::class,
            CaregiverLinkRepository::class,
            WaterGoalRepository::class,
            AppSettingsRepository::class,
            InterruptionBudgetRepository::class,
            SyncStateRepository::class,
        )

    private val storeRepositories: List<KClass<*>> =
        listOf(
            FireTelemetryRepository::class,
            ArmedAlarmRepository::class,
        )

    private class Counting(
        private val delegate: DatabaseDriverFactory,
        private val sink: MutableList<SqlDriver>,
    ) : DatabaseDriverFactory {
        override fun createDriver(): SqlDriver = delegate.createDriver().also { sink.add(it) }
    }

    private class CountingStore(
        private val delegate: StoreDriverFactory,
        private val sink: MutableList<SqlDriver>,
    ) : StoreDriverFactory {
        override fun createDriver(): SqlDriver = delegate.createDriver().also { sink.add(it) }
    }

    private fun start(): KoinApplication {
        val suffix = UUID.randomUUID().toString().take(8)
        val name = "k-$suffix.db"
        val storeName = "ks-$suffix.db"
        context.getDatabasePath(name).parentFile?.mkdirs()
        val factory = Counting(AndroidDatabaseDriverFactory(context, name), sharedDrivers)
        val storeFactory = CountingStore(AndroidStoreDriverFactory(context, storeName), storeDrivers)
        return koinApplication { modules(momTimeModules(context, factory, storeFactory)) }.also { application = it }
    }

    @After
    fun tearDown() {
        application?.close()
        (sharedDrivers + storeDrivers).forEach { runCatching { it.close() } }
    }

    @Test
    fun `every repository of both databases resolves`() {
        val koin = start().koin
        for (type in sharedRepositories + storeRepositories) {
            assertNotNull("${type.simpleName} did not resolve", koin.get<Any>(type))
        }
    }

    // The databases, with the generated query that would skip the event append, are reachable only
    // through a repository. Resolving either fails; so does resolving a driver itself.
    @Test
    fun `resolving either database fails`() {
        val koin = start().koin
        for (type in sharedRepositories + storeRepositories) koin.get<Any>(type)

        val hidden =
            listOf(
                Class.forName("com.momtime.shared.data.MomTimeDatabase").kotlin,
                Class.forName("com.momtime.android.store.db.AndroidStoreDatabase").kotlin,
                SqlDriver::class,
            )
        for (type in hidden) {
            try {
                koin.get<Any>(type)
                fail("${type.simpleName} resolved from the graph")
            } catch (expected: NoDefinitionFoundException) {
                // The graph does not hold it.
            }
        }
    }

    // A second driver on one file brings SQLITE_BUSY back (ADR 0045), so all repositories of a database
    // share exactly one, and the two databases have one each.
    @Test
    fun `each database gets exactly one driver`() {
        val koin = start().koin
        for (type in sharedRepositories + storeRepositories) koin.get<Any>(type)

        assertEquals("a second shared driver was created", 1, sharedDrivers.size)
        assertEquals("a second store driver was created", 1, storeDrivers.size)
    }

    @Test
    fun `the driver factories and the repositories are singles`() {
        val koin = start().koin
        assertSame(koin.get<DatabaseDriverFactory>(), koin.get<DatabaseDriverFactory>())
        assertSame(koin.get<StoreDriverFactory>(), koin.get<StoreDriverFactory>())
        assertSame(koin.get<OccurrenceRepository>(), koin.get<OccurrenceRepository>())
        assertSame(koin.get<ArmedAlarmRepository>(), koin.get<ArmedAlarmRepository>())
    }

    // Nothing opens the store until a store repository is used: resolving shared repositories alone
    // must not create a store driver, so the shared path never waits on the store.
    @Test
    fun `the store is not opened by the shared repositories`() {
        val koin = start().koin
        for (type in sharedRepositories) koin.get<Any>(type)

        assertEquals(0, storeDrivers.size)
    }
}
