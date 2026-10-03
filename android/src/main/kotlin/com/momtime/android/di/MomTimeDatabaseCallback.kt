package com.momtime.android.di

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import kotlin.time.Clock

/**
 * How the app configures the shared database, differing from `AndroidSqliteDriver.Callback` in three
 * ways: foreign keys are enforced (ADR 0043), the journal mode is set explicitly to a rollback
 * journal (ADR 0047), and a corrupt database is moved aside instead of deleted (ADR 0044). The
 * android store is configured the same way by [AndroidStoreCallback].
 */
class MomTimeDatabaseCallback(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    markerFile: File,
    clock: Clock,
) : AndroidSqliteDriver.Callback(schema) {
    private val corruption = CorruptionHandler(markerFile, clock)

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.configureForMomTime()
    }

    override fun onCorruption(db: SupportSQLiteDatabase) = corruption.handle(db)
}

/** The same configuration for the android store (ADR 0048). */
class AndroidStoreCallback(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    markerFile: File,
    clock: Clock,
) : AndroidSqliteDriver.Callback(schema) {
    private val corruption = CorruptionHandler(markerFile, clock)

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.configureForMomTime()
    }

    override fun onCorruption(db: SupportSQLiteDatabase) = corruption.handle(db)
}
