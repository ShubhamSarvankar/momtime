package com.momtime.android.di

/**
 * Names of the files the databases live in. The backup rules in `res/xml` name the same files, and a
 * test pins the two together, so a rename cannot silently take a database into backup or put a
 * quarantined copy into it.
 */
object DatabaseFiles {
    /** The shared database: the event log, the schedule, the record of authority. Backed up. */
    const val NAME = "momtime.db"
    const val JOURNAL_NAME = "$NAME-journal"

    /** The corrupt shared database, moved aside (only the most recent is kept). Never backed up. */
    const val CORRUPT_NAME = "$NAME.corrupt"

    /** Written when corruption is found; the reliability view reads it (a later PR). In the no-backup directory. */
    const val CORRUPTION_MARKER_NAME = "database-corruption.marker"

    /** The android store: what the shared schema must not hold (ADR 0048). Never backed up. */
    const val STORE_NAME = "momtime_android.db"
    const val STORE_JOURNAL_NAME = "$STORE_NAME-journal"
    const val STORE_CORRUPT_NAME = "$STORE_NAME.corrupt"
    const val STORE_CORRUPTION_MARKER_NAME = "store-corruption.marker"
}
