package com.momtime.shared.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

// SQLDelight maps INTEGER -> Long and has no built-in Boolean/Instant/LocalDate/TimeZone
// adapters configured here, so conversions are centralised in this file rather than repeated
// at every call site.

internal fun Instant.toDb(): Long = toEpochMilliseconds()

internal fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)

internal fun Instant?.toDbOrNull(): Long? = this?.toEpochMilliseconds()

internal fun Long?.toInstantOrNull(): Instant? = this?.let { Instant.fromEpochMilliseconds(it) }

internal fun Boolean.toDb(): Long = if (this) 1L else 0L

internal fun Long.toBoolean(): Boolean = this != 0L

internal fun TimeZone.toDb(): String = id

internal fun String.toTimeZone(): TimeZone = TimeZone.of(this)

internal fun LocalDate.toDb(): String = toString()

internal fun String.toLocalDate(): LocalDate = LocalDate.parse(this)
