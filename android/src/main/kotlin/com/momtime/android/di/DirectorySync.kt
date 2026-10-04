package com.momtime.android.di

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Makes the directory entries of a directory durable (ADR 0055). A file's bytes are durable once the file is
 * synced, but the entry that names it (a rename, a delete) lives in the directory and is durable only when
 * the directory is synced too. Process death loses nothing: the kernel still holds what was written. Power
 * loss is different, and the corruption marker and the `.corrupt` copy are the record that the schedule was
 * lost, so they are synced the same way the marker's bytes are.
 *
 * A seam so a test can see when it is called. A failure to sync is not a failure of the handler: it is
 * best effort, and the handler must finish whatever happens.
 */
fun interface DirectorySync {
    fun sync(directory: File)
}

/** The production seam: `open`, `fsync` and `close` on the directory through `android.system.Os`. */
object OsDirectorySync : DirectorySync {
    @Suppress("TooGenericExceptionCaught")
    override fun sync(directory: File) {
        try {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (_: Exception) {
            // Best effort. A directory that cannot be opened for reading has nothing more to give.
        }
    }
}
