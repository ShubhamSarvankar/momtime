package com.momtime.android.di

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlin.time.Clock

/**
 * Configuration both databases share. `onConfigure` is the only place it can run: it comes after the
 * framework has applied its own configuration to the connection and before `onCreate` and
 * `onUpgrade`, and `setForeignKeyConstraintsEnabled` throws inside a transaction.
 */
internal fun SupportSQLiteDatabase.configureForMomTime() {
    setForeignKeyConstraintsEnabled(true)
    useRollbackJournal()
}

/**
 * The journal mode is set, not left to a default (ADR 0047). `disableWriteAheadLogging` clears the
 * framework's compatibility WAL flag as well as WAL, which a global setting can turn on for an app
 * that chooses nothing. The pragma then fixes the mode to a rollback journal whatever the platform
 * default is, so the device value does not depend on one. Under WAL the newest transactions sit in a
 * separate file that the backup rules do not include, so a restore could lose them. It uses `query`
 * and consumes the cursor, because the cursor is lazy and only runs the pragma when it is read.
 */
internal fun SupportSQLiteDatabase.useRollbackJournal() {
    disableWriteAheadLogging()
    query("PRAGMA journal_mode=TRUNCATE").use { it.moveToFirst() }
}

/**
 * What happens when the framework reports a corrupt database, on open and in the middle of a query
 * (ADR 0044, ADR 0049, ADR 0051). The database is closed first, then the file is moved aside as
 * `<name>.corrupt` (only the most recent is kept), then a durable marker is written. If the file cannot
 * be preserved it is deleted: reminders must keep working.
 *
 * What happens next depends on when the corruption was found, and on which database:
 * - **On open**, the framework opens a fresh database in place of the file just moved aside, so nothing
 *   holds a closed database and nothing more is done.
 * - **In the middle of a query**, the database that was open is now closed and the process still holds
 *   it. [onClosedByCorruption] tells the owner of a database that can be reopened (the android store).
 *   [processEnd] ends the process for the database that cannot (the shared database), after the
 *   marker is durable. Either is null where it does not apply.
 */
internal class CorruptionHandler(
    private val markerFile: File,
    private val clock: Clock,
    private val processEnd: ProcessEnd? = null,
    private val onClosedByCorruption: (() -> Unit)? = null,
) {
    fun handle(db: SupportSQLiteDatabase) {
        // A database that was open when corruption was found was in use. One still being opened is not.
        val midUse = db.isOpen
        val path = db.path
        runCatching { db.close() }
        // An in-memory database has no file to keep, and nothing to replace.
        if (path == null) return
        val preserved = quarantine(File(path))
        CorruptionMarker.write(markerFile, CorruptionMarker(clock.now(), preserved))
        if (midUse) {
            onClosedByCorruption?.invoke()
            processEnd?.end()
        }
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
