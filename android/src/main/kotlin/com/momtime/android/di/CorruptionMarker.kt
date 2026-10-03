package com.momtime.android.di

import java.io.File
import java.io.FileOutputStream
import kotlin.time.Instant

/**
 * A record that the database was found corrupt and replaced (ADR 0044). It is a small file in the
 * no-backup directory, so a restore onto another phone never carries it, and so the reliability
 * view can read it without opening the database that was just replaced. No UI reads it yet.
 *
 * [preserved] is whether the corrupt file was kept as a `.corrupt` copy. If keeping it failed, the
 * database was deleted instead, because reminders must keep working.
 *
 * The write is durable: the bytes are synced to storage before the file is renamed into place, so a
 * process that ends straight afterwards (ADR 0051) leaves the marker behind.
 */
data class CorruptionMarker(
    val corruptedAt: Instant,
    val preserved: Boolean,
) {
    companion object {
        fun write(
            file: File,
            marker: CorruptionMarker,
        ) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            val bytes =
                "corruptedAtMillis=${marker.corruptedAt.toEpochMilliseconds()}\npreserved=${marker.preserved}\n"
                    .toByteArray()
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                file.writeBytes(bytes)
                temp.delete()
            }
        }

        fun read(file: File): CorruptionMarker? {
            val values =
                if (file.isFile) {
                    file
                        .readLines()
                        .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
                        .associate { (key, value) -> key to value }
                } else {
                    emptyMap()
                }
            val millis = values["corruptedAtMillis"]?.toLongOrNull()
            return millis?.let { CorruptionMarker(Instant.fromEpochMilliseconds(it), values["preserved"] == "true") }
        }
    }
}
