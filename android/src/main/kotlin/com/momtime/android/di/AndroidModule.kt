package com.momtime.android.di

import android.content.Context
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.di.clockModule
import com.momtime.shared.di.sharedModule
import org.koin.core.module.Module
import org.koin.dsl.module

/** What android adds to the graph: the driver factory, and nothing else. */
fun androidModule(driverFactory: DatabaseDriverFactory): Module =
    module {
        single<DatabaseDriverFactory> { driverFactory }
    }

/**
 * Every module the app starts Koin with. The graph exposes the repositories, the clock and the
 * driver factory. It never exposes the database or any generated query type, and the structural
 * check (`verifyNoGeneratedQueries`) keeps android sources from naming them.
 */
fun momTimeModules(
    context: Context,
    driverFactory: DatabaseDriverFactory = AndroidDatabaseDriverFactory(context),
): List<Module> = listOf(androidModule(driverFactory), clockModule, sharedModule())
