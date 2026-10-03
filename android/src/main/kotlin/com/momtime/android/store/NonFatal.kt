package com.momtime.android.store

/**
 * The android store is diagnostics and an armed record the watchdog recomputes, so a failure in it must
 * never stop an alarm (ADR 0051). Every call on the alarm and watchdog paths goes through here: a failure
 * returns [onFailure] instead of throwing. A failed read of the armed record is therefore "record
 * missing", which sends the watchdog to its repair path, and that is acceptable.
 *
 * It catches `RuntimeException`, which is what the framework throws (`SQLiteException`,
 * `IllegalStateException` for a closed database) and no more: an `Error` still propagates.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> nonFatal(
    onFailure: T,
    block: () -> T,
): T =
    try {
        block()
    } catch (_: RuntimeException) {
        onFailure
    }
