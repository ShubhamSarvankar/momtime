package com.momtime.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.store.ArmingContextRepository
import com.momtime.android.store.BootInstantRepository
import com.momtime.android.store.ClockChangeRepository
import com.momtime.android.store.CountingStoreFailures
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.android.store.LogOnlyStoreFailures
import com.momtime.android.store.ReliabilityCheckRepository
import com.momtime.android.store.SqlDelightArmedAlarmRepository
import com.momtime.android.store.SqlDelightArmingContextRepository
import com.momtime.android.store.SqlDelightBootInstantRepository
import com.momtime.android.store.SqlDelightClockChangeRepository
import com.momtime.android.store.SqlDelightFireTelemetryRepository
import com.momtime.android.store.SqlDelightReliabilityCheckRepository
import com.momtime.android.store.SqlDelightStoreFailureRepository
import com.momtime.android.store.StoreFailureRepository
import com.momtime.android.store.StoreFailures
import com.momtime.android.store.db.AndroidStoreDatabase
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.di.clockModule
import com.momtime.shared.di.sharedModule
import org.koin.core.module.Module
import org.koin.dsl.module

/** What android adds to the graph for the shared database: the driver factory, and nothing else. */
fun androidModule(driverFactory: DatabaseDriverFactory): Module =
    module {
        single<DatabaseDriverFactory> { driverFactory }
    }

/**
 * Holds the one live [AndroidStoreDatabase] and its driver. The database is not a Koin definition, so
 * `get<AndroidStoreDatabase>()` fails: the graph exposes the store's repositories and nothing below them,
 * as it does for the shared database.
 *
 * Unlike the shared database, the store can be reopened (ADR 0051). When corruption found in the middle
 * of a query has closed its driver, the next [database] call closes that driver and builds a fresh one,
 * under a lock, so there is exactly one live driver at any time. The repositories read the database
 * through [database] on every call and keep no reference to it.
 */
internal class StoreDatabaseHolder(
    private val factory: StoreDriverFactory,
) {
    private val lock = Any()
    private var driver: SqlDriver? = null
    private var current: AndroidStoreDatabase? = null

    @Volatile
    private var closedByCorruption = false

    fun database(): AndroidStoreDatabase =
        synchronized(lock) {
            val existing = current
            if (existing != null && !closedByCorruption) return existing
            driver?.let { runCatching { it.close() } }
            val fresh = factory.createDriver { closedByCorruption = true }
            closedByCorruption = false
            driver = fresh
            AndroidStoreDatabase(fresh).also { current = it }
        }
}

/** The android store's repositories, over one live driver. */
fun androidStoreModule(
    storeFactory: StoreDriverFactory,
    failures: StoreFailures,
): Module =
    module {
        single<StoreDriverFactory> { storeFactory }
        single<StoreFailures> {
            // Counts are written through to the store the first time one is needed, not when the graph is built.
            failures.also { (it as? CountingStoreFailures)?.persistThrough { get<StoreFailureRepository>() } }
        }
        single { StoreDatabaseHolder(get()) }
        single<FireTelemetryRepository> {
            SqlDelightFireTelemetryRepository(get<StoreDatabaseHolder>()::database, get())
        }
        single<ArmedAlarmRepository> {
            SqlDelightArmedAlarmRepository(get<StoreDatabaseHolder>()::database, get())
        }
        single<ReliabilityCheckRepository> {
            SqlDelightReliabilityCheckRepository(get<StoreDatabaseHolder>()::database, get())
        }
        single<ArmingContextRepository> {
            SqlDelightArmingContextRepository(get<StoreDatabaseHolder>()::database, get())
        }
        single<BootInstantRepository> {
            SqlDelightBootInstantRepository(get<StoreDatabaseHolder>()::database, get())
        }
        single<ClockChangeRepository> {
            SqlDelightClockChangeRepository(get<StoreDatabaseHolder>()::database, get())
        }
        // The failure counts are written through to the store. The repository that holds them reports its own
        // failures to the log only, so a store that cannot count never counts itself into a loop (ADR 0070).
        single<StoreFailureRepository> {
            SqlDelightStoreFailureRepository(get<StoreDatabaseHolder>()::database, LogOnlyStoreFailures())
        }
    }

/**
 * Every module the app starts Koin with. The graph exposes the repositories of both databases, the
 * clock and the two driver factories. It never exposes either database or any generated query type,
 * and the structural check (`verifyNoGeneratedQueries`) keeps android sources from naming them.
 */
fun momTimeModules(
    context: Context,
    driverFactory: DatabaseDriverFactory = AndroidDatabaseDriverFactory(context),
    storeFactory: StoreDriverFactory = AndroidStoreDriverFactory(context),
    storeFailures: StoreFailures = CountingStoreFailures(),
    arming: Module = armingModule(context, delivery = DeliveryWiring(enabled = true)),
): List<Module> =
    listOf(
        androidModule(driverFactory),
        androidStoreModule(storeFactory, storeFailures),
        arming,
        clockModule,
        sharedModule(),
    )
