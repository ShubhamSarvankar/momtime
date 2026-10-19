package com.momtime.android.settings

import android.content.Context
import android.content.SharedPreferences
import com.momtime.android.ringer.VibrationPattern
import com.momtime.shared.domain.Criticality
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Her choices that only Android has (ADR 0065): how long a ring may go unanswered before the louder backup sound,
 * and the vibration pattern of each template. They are platform capability differences, so they cannot enter the
 * shared schema (invariant 5, ARCHITECTURE.md sections 5.5 and 12), and they are hers, so they cannot go in the
 * android store, which is excluded from backup (ADR 0048). They live in one `SharedPreferences` file of android's
 * own, which the backup rules include by name (`backup_rules.xml`, `data_extraction_rules.xml`), and a test fails
 * if the rules and this name drift apart.
 *
 * Their settings screen is Phase 3. Until then every value is its default, and nothing here is written by the
 * app itself: a template's vibration is its criticality's pattern until she chooses another.
 *
 * Nothing in the file is a log line: a template id is a key and never printed (invariant 11).
 */
class AndroidSettings(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * How long a ring may go unanswered before the louder backup sound starts, or null if she has turned it off.
     * [DEFAULT_BACKUP_DELAY] by default.
     */
    fun backupSoundDelay(): Duration? {
        val millis = prefs.getLong(KEY_BACKUP_DELAY_MS, DEFAULT_BACKUP_DELAY.inWholeMilliseconds)
        return if (millis <= 0L) null else millis.milliseconds
    }

    /** Sets the interval, or turns the backup sound off with null. */
    fun setBackupSoundDelay(delay: Duration?) {
        prefs.edit().putLong(KEY_BACKUP_DELAY_MS, delay?.inWholeMilliseconds ?: 0L).apply()
    }

    /**
     * The pattern the ring of [templateId] vibrates with: hers if she chose one, otherwise the default for
     * [criticality], the occurrence's own. A Gentle occurrence vibrates nothing whatever is stored (ADR 0089 item
     * 7); the stored choice is kept, not deleted, so that a template edited back to Critical or Standard vibrates as
     * she chose. An unknown stored name reads as the default, so a value from a newer version cannot crash.
     */
    fun vibrationFor(
        templateId: String,
        criticality: Criticality,
    ): VibrationPattern {
        if (criticality == Criticality.GENTLE) return VibrationPattern.NONE
        return prefs.getString(vibrationKey(templateId), null)?.let(VibrationPattern::fromName)
            ?: VibrationPattern.defaultFor(criticality)
    }

    /** Chooses [pattern] for [templateId], or goes back to the criticality's default with null. */
    fun setVibration(
        templateId: String,
        pattern: VibrationPattern?,
    ) {
        val edit = prefs.edit()
        if (pattern ==
            null
        ) {
            edit.remove(vibrationKey(templateId))
        } else {
            edit.putString(vibrationKey(templateId), pattern.name)
        }
        edit.apply()
    }

    /**
     * Whether she has agreed to share how reliably reminders arrive on this phone (ARCHITECTURE.md section 5.10).
     * Off by default. It gates upload and nothing else: v1 has no upload, and what is recorded on the device is the
     * same either way (ADR 0070).
     */
    fun shareReliabilityOptIn(): Boolean = prefs.getBoolean(KEY_RELIABILITY_OPT_IN, false)

    fun setShareReliabilityOptIn(value: Boolean) {
        prefs.edit().putBoolean(KEY_RELIABILITY_OPT_IN, value).apply()
    }

    /**
     * Whether the runtime request for notifications has been made (API 33 and above). After it, the system will not
     * show
     * the dialog again, so the setup screen opens the app's notification settings instead (ADR 0072).
     */
    fun notificationsRequested(): Boolean = prefs.getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)

    fun markNotificationsRequested() {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_REQUESTED, true).apply()
    }

    private fun vibrationKey(templateId: String) = "$KEY_VIBRATION_PREFIX$templateId"

    companion object {
        /** The `SharedPreferences` file's name. The backup rules include `shared_prefs/<this>.xml`. */
        const val FILE_NAME = "momtime_android_settings"

        /** The path the backup rules name, in the `sharedpref` domain. */
        const val BACKUP_PATH = "$FILE_NAME.xml"

        /** A placeholder default: two minutes unanswered. The ladder's next rung is five minutes in. */
        val DEFAULT_BACKUP_DELAY: Duration = 2.minutes

        private const val KEY_BACKUP_DELAY_MS = "backup_sound_delay_ms"
        private const val KEY_VIBRATION_PREFIX = "vibration."
        private const val KEY_RELIABILITY_OPT_IN = "share_reliability_opt_in"
        private const val KEY_NOTIFICATIONS_REQUESTED = "notifications_requested"
    }
}
