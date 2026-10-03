package com.momtime.android.di

import android.content.Context
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.android.store.SqlDelightArmedAlarmRepository
import com.momtime.android.store.SqlDelightFireTelemetryRepository
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
 * Holds the one [AndroidStoreDatabase], built on first use. The database is not a Koin definition,
 * so `get<AndroidStoreDatabase>()` fails: the graph exposes the store's repositories and nothing
 * below them, as it does for the shared database.
 */
internal class StoreDatabaseHolder(
    factory: StoreDriverFactory,
) {
    val database: AndroidStoreDatabase by lazy { AndroidStoreDatabase(factory.createDriver()) }
}

/** The android store's repositories, over one driver. */
fun androidStoreModule(storeFactory: StoreDriverFactory): Module =
    module {
        single<StoreDriverFactory> { storeFactory }
        single { StoreDatabaseHolder(get()) }
        single<FireTelemetryRepository> { SqlDelightFireTelemetryRepository(get<StoreDatabaseHolder>().database) }
        single<ArmedAlarmRepository> { SqlDelightArmedAlarmRepository(get<StoreDatabaseHolder>().database) }
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
): List<Module> = listOf(androidModule(driverFactory), androidStoreModule(storeFactory), clockModule, sharedModule())
