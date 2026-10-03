package com.momtime.android.di

/**
 * Names of the files the shared database lives in. The backup rules in `res/xml` name the same
 * files, and a test pins the two together, so a rename cannot silently take the database out of
 * backup or put the quarantined copy into it.
 */
object DatabaseFiles {
    const val NAME = "momtime.db"
    const val JOURNAL_NAME = "$NAME-journal"

    /** The corrupt database, moved aside (only the most recent is kept). Never backed up. */
    const val CORRUPT_NAME = "$NAME.corrupt"

    /** Written when corruption is found; the reliability view reads it (a later PR). In the no-backup directory. */
    const val CORRUPTION_MARKER_NAME = "database-corruption.marker"
}
