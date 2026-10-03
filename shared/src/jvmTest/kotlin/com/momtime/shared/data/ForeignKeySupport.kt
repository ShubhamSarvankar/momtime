package com.momtime.shared.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Rows `PRAGMA foreign_key_check` reports as violating a declared foreign key: "table rowid ->
 * parent". Empty means every foreign key holds. This finds orphans that enforcement did not stop,
 * for example ones a table rebuild left behind (ADR 0043).
 */
internal fun SqlDriver.foreignKeyViolations(): List<String> =
    executeQuery(
        identifier = null,
        sql = "PRAGMA foreign_key_check",
        mapper = { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) {
                rows += "${cursor.getString(0)} rowid=${cursor.getLong(1)} -> ${cursor.getString(2)}"
            }
            QueryResult.Value(rows.toList())
        },
        parameters = 0,
    ).value

internal fun SqlDriver.pragma(name: String): String =
    executeQuery(
        identifier = null,
        sql = "PRAGMA $name",
        mapper = { cursor ->
            check(cursor.next().value) { "PRAGMA $name returned no row" }
            QueryResult.Value(cursor.getString(0) ?: cursor.getLong(0).toString())
        },
        parameters = 0,
    ).value
