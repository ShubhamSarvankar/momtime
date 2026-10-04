package com.momtime.android.store

/**
 * Every call on the alarm and watchdog paths goes through here (ADR 0054). A failure goes to [failures] and
 * the call returns [fallback]. A failed read of the armed record is therefore "record missing", which sends
 * the watchdog to its repair path, and that is acceptable because the failure was counted.
 *
 * It catches `RuntimeException`, which is what the framework throws (`SQLiteException`,
 * `IllegalStateException` for a closed database) and no more: an `Error` still propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> nonFatal(
    failures: StoreFailures,
    operation: String,
    fallback: T,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: RuntimeException) {
        failures.onFailure(operation, e)
        fallback
    }
