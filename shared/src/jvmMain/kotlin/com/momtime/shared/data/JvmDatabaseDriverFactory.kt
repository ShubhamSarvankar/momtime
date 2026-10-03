package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

/**
 * Opens a JDBC SQLite driver with foreign key enforcement on. `JdbcSqliteDriver` leaves SQLite's
 * default (off) in place, so a declared foreign key is never checked unless the connection asks.
 * Every JVM driver in the project is opened through here (ADR 0043). [properties] are copied and
 * can add settings, but cannot turn enforcement off.
 */
fun openJvmSqliteDriver(
    url: String,
    properties: Properties = Properties(),
): JdbcSqliteDriver {
    val withForeignKeys = Properties().apply { putAll(properties) }
    withForeignKeys.setProperty("foreign_keys", "true")
    return JdbcSqliteDriver(url, withForeignKeys)
}

/** Used by the server (a real file) and by jvmTest (in-memory) — see the two constructors. */
class JvmDatabaseDriverFactory(
    private val jdbcUrl: String,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        val driver = openJvmSqliteDriver(jdbcUrl)
        MomTimeDatabase.Schema.create(driver)
        return driver
    }

    companion object {
        fun inMemory(): JvmDatabaseDriverFactory = JvmDatabaseDriverFactory(JdbcSqliteDriver.IN_MEMORY)
    }
}
