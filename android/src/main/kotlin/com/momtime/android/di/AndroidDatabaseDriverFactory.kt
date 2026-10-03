package com.momtime.android.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.data.MomTimeDatabase
import java.io.File
import kotlin.time.Clock

/**
 * The production driver factory. One database file, [DatabaseFiles.NAME], in the app's database
 * directory. It is the only place android code builds a driver for the shared database, and the
 * shared module calls it exactly once (a second driver on the same file brings SQLITE_BUSY back,
 * ADR 0036, ADR 0045).
 *
 * Schema creation and migration are the framework's: `onCreate` for a new file, `onUpgrade` from
 * the file's `user_version`, both inside a transaction.
 *
 * [processEnd] is how the process ends after corruption found in the middle of a query (ADR 0051).
 */
class AndroidDatabaseDriverFactory(
    private val context: Context,
    private val name: String = DatabaseFiles.NAME,
    private val clock: Clock = Clock.System,
    private val processEnd: ProcessEnd = KillOwnProcess,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = MomTimeDatabase.Schema,
            context = context,
            name = name,
            callback =
                MomTimeDatabaseCallback(
                    schema = MomTimeDatabase.Schema,
                    markerFile = File(context.noBackupFilesDir, DatabaseFiles.CORRUPTION_MARKER_NAME),
                    clock = clock,
                    processEnd = processEnd,
                ),
        )
}
