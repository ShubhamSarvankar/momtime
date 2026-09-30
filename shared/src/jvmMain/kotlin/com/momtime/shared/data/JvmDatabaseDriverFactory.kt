package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/** Used by the server (a real file) and by jvmTest (in-memory) — see the two constructors. */
class JvmDatabaseDriverFactory(
    private val jdbcUrl: String,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(jdbcUrl)
        MomTimeDatabase.Schema.create(driver)
        return driver
    }

    companion object {
        fun inMemory(): JvmDatabaseDriverFactory = JvmDatabaseDriverFactory(JdbcSqliteDriver.IN_MEMORY)
    }
}
