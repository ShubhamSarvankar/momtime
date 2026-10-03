package com.momtime.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.momtime.android.store.db.AndroidStoreDatabase
import java.io.File
import kotlin.time.Clock

/** Builds the driver for the android store. One per process, like the shared database's (ADR 0045). */
interface StoreDriverFactory {
    fun createDriver(): SqlDriver
}

class AndroidStoreDriverFactory(
    private val context: Context,
    private val name: String = DatabaseFiles.STORE_NAME,
    private val clock: Clock = Clock.System,
) : StoreDriverFactory {
    override fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = AndroidStoreDatabase.Schema,
            context = context,
            name = name,
            callback =
                AndroidStoreCallback(
                    schema = AndroidStoreDatabase.Schema,
                    markerFile = File(context.noBackupFilesDir, DatabaseFiles.STORE_CORRUPTION_MARKER_NAME),
                    clock = clock,
                ),
        )
}
