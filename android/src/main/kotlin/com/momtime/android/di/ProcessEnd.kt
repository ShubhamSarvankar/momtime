package com.momtime.android.di

import android.os.Process

/**
 * How the app ends its own process, as a seam so a test can observe it instead of dying (ADR 0051).
 * The shared database's corruption handler ends the process after a corruption found in the middle of
 * a query, because every component holds repositories and, from Phase 3, the UI holds query listeners
 * over the database that was just closed: a process that ends has no stale state to go silent.
 *
 * Ending the process is not a force stop. Alarms and jobs survive it, and the next start, from an
 * alarm, the watchdog or the user, opens a fresh database.
 */
fun interface ProcessEnd {
    fun end()
}

/** The production seam: kills this process. */
object KillOwnProcess : ProcessEnd {
    override fun end() = Process.killProcess(Process.myPid())
}
