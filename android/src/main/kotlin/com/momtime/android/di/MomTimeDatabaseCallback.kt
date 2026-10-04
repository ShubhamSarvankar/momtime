package com.momtime.android.di

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import kotlin.time.Clock

/**
 * How the app configures the shared database, differing from `AndroidSqliteDriver.Callback` in four
 * ways: foreign keys are enforced (ADR 0043), the journal mode is set explicitly to a rollback
 * journal (ADR 0047), a corrupt database is moved aside instead of deleted (ADR 0044), and corruption
 * found in the middle of a query ends the process once the marker is durable (ADR 0051), because the
 * process would otherwise hold a closed database that every component and, from Phase 3, every query
 * listener still points at. The android store is configured the same way by [AndroidStoreCallback],
 * except that it reopens instead.
 */
class MomTimeDatabaseCallback(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    markerFile: File,
    clock: Clock,
    processEnd: ProcessEnd,
    resetNotifier: ResetNotifier? = null,
) : AndroidSqliteDriver.Callback(schema) {
    private val corruption =
        CorruptionHandler(markerFile, clock, processEnd = processEnd, resetNotifier = resetNotifier)

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.configureForMomTime()
    }

    override fun onCorruption(db: SupportSQLiteDatabase) = corruption.handle(db)
}

/**
 * The same configuration for the android store (ADR 0048). Corruption found in the middle of a query
 * calls [onClosedByCorruption], so the store's holder replaces the closed driver with a fresh one
 * (ADR 0051). The process is never ended for the store: its only consumers are our own telemetry and
 * armed record code, with no listeners, and ending the process would cut off a valid ring over a lost
 * telemetry row.
 */
class AndroidStoreCallback(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    markerFile: File,
    clock: Clock,
    onClosedByCorruption: () -> Unit,
) : AndroidSqliteDriver.Callback(schema) {
    private val corruption = CorruptionHandler(markerFile, clock, onClosedByCorruption = onClosedByCorruption)

    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.configureForMomTime()
    }

    override fun onCorruption(db: SupportSQLiteDatabase) = corruption.handle(db)
}
