package com.momtime.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.momtime.android.store.db.AndroidStoreDatabase
import java.io.File
import kotlin.time.Clock

/**
 * Builds the driver for the android store. One live driver at a time (ADR 0045, ADR 0051).
 * [onClosedByCorruption] is called when corruption found in the middle of a query has closed the
 * driver this call returned, so the caller can replace it.
 */
interface StoreDriverFactory {
    fun createDriver(onClosedByCorruption: () -> Unit): SqlDriver
}

class AndroidStoreDriverFactory(
    private val context: Context,
    private val name: String = DatabaseFiles.STORE_NAME,
    private val clock: Clock = Clock.System,
) : StoreDriverFactory {
    override fun createDriver(onClosedByCorruption: () -> Unit): SqlDriver =
        AndroidSqliteDriver(
            schema = AndroidStoreDatabase.Schema,
            context = context,
            name = name,
            callback =
                AndroidStoreCallback(
                    schema = AndroidStoreDatabase.Schema,
                    markerFile = File(context.noBackupFilesDir, DatabaseFiles.STORE_CORRUPTION_MARKER_NAME),
                    clock = clock,
                    onClosedByCorruption = onClosedByCorruption,
                ),
        )
}
