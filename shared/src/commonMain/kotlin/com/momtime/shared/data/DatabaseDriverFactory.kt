package com.momtime.shared.data

import app.cash.sqldelight.db.SqlDriver

/**
 * Platform-specific driver construction lives behind this interface, not in shared's schema or
 * domain code (ADR 0027 — shared has no android target, so the Android implementation lives in
 * the android module, not in a shared/androidMain source set).
 */
interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
