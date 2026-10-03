package com.momtime.android.di

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import kotlin.time.Clock

/**
 * How the app configures its database, differing from `AndroidSqliteDriver.Callback` in two ways.
 *
 * Foreign keys are enforced (ADR 0043). The Android framework leaves them off, and this is the
 * only place that can turn them on: `onConfigure` runs before `onCreate` and `onUpgrade`, and the
 * call throws inside a transaction.
 *
 * Corruption does not delete the database (ADR 0044). The default `onCorruption` deletes the file,
 * and this database holds the device's record of authority. The corrupt file is moved aside as a
 * `.corrupt` copy, only the most recent is kept, and the framework then opens a fresh database so
 * reminders keep working. A marker records that it happened.
 *
 * Journal mode is deliberately not touched: WAL is not enabled, so the database stays one file
 * for Auto Backup and the framework's connection pool stays at one connection.
 */
class MomTimeDatabaseCallback(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    private val markerFile: File,
    private val clock: Clock,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCorruption(db: SupportSQLiteDatabase) {
        val path = db.path
        runCatching { db.close() }
        // An in-memory database has no file to keep, and nothing to replace.
        if (path == null) return
        val preserved = quarantine(File(path))
        CorruptionMarker.write(markerFile, CorruptionMarker(clock.now(), preserved))
    }

    private fun quarantine(database: File): Boolean {
        val corrupt = File(database.parentFile, database.name + CORRUPT_SUFFIX)
        corrupt.delete()
        val preserved = database.renameTo(corrupt) || copyThenDelete(database, corrupt)
        if (!preserved) database.delete()
        // The sidecar files belong to the corrupt database, never to the fresh one.
        for (suffix in listOf("-journal", "-wal", "-shm")) File(database.path + suffix).delete()
        return preserved
    }

    private fun copyThenDelete(
        from: File,
        to: File,
    ): Boolean =
        runCatching {
            from.copyTo(to, overwrite = true)
            from.delete()
        }.getOrDefault(false)

    private companion object {
        const val CORRUPT_SUFFIX = ".corrupt"
    }
}
