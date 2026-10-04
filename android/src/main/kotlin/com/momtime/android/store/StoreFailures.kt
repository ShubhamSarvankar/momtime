package com.momtime.android.store

/**
 * What happens to a failure in the android store (ADR 0051, ADR 0054). The store is diagnostics and an
 * armed record the watchdog recomputes, so on the alarm path a failure in it must never stop an alarm: in
 * production it is counted and logged ([CountingStoreFailures]) and the call returns its fallback. A
 * failure that is swallowed and not counted would hide a bug: a broken armed record write shows up as a
 * repair on every watchdog pass and nobody would notice. In tests the store runs strict instead, and a
 * failure is thrown, so a store bug fails the suite.
 */
interface StoreFailures {
    /** [operation] names the repository call that failed. [cause] is for counting and for strict mode. */
    fun onFailure(
        operation: String,
        cause: RuntimeException,
    )
}
