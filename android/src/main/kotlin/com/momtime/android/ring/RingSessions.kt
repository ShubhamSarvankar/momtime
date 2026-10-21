package com.momtime.android.ring

import com.momtime.shared.engine.OccurrenceAction

/**
 * One occurrence as the ring screen shows it. [title], [dosage] and [doctorInstructions] are hers, shown exactly
 * as she typed them and never parsed (CLAUDE.md). Only fields that exist in the model are here.
 *
 * [alarmSlot] names the occurrence to a notification button, which carries a slot and never an id. [actions] is
 * what the domain says she may do with it as of when it joined the session (ADR 0066): an action that is not in it
 * is not offered, so a fourth snooze is never a button. The screen never decides this itself.
 */
data class RingItem(
    val occurrenceId: String,
    val title: String,
    val dosage: String?,
    val doctorInstructions: String?,
    val alarmSlot: Int = 0,
    val actions: Set<OccurrenceAction> = emptySet(),
)

/** How the ring session's notification is posted: its channel, and whether it carries a full screen intent. */
data class RingStyle(
    val channelId: String,
    val fullScreenIntent: Boolean,
)

/** What taking an occurrence out of the session left (ADR 0066). */
data class RingResolved(
    /** True if the occurrence was in what the screen lists. */
    val wasListed: Boolean,
    /** How many occurrences are still listed. */
    val remaining: Int,
    /** True if this was the last one listed while the sound was on, so the ring ended with it. */
    val ended: Boolean,
)

/** What joining the session did. */
enum class RingJoin {
    /**
     * The sound was off: this fire starts it. The occurrence may already have been listed, after the cap or "Stop the
     * sound" (ADR 0090 item 6), or this may be the first occurrence due; either way the ringer starts.
     */
    STARTED,

    /** The sound is on and the occurrence is listed: the ring in progress continues. */
    CONTINUED,

    /** The sound is on and the occurrence was not listed: it joined, and no second ringer began. */
    JOINED,
}

/**
 * The ring session (ADR 0062, ADR 0090): the occurrences that are currently due, and whether the sound is on for
 * them. One at a time. A fire for an occurrence that is listed while the sound is on continues the ring (no restart,
 * no second screen); a fire for another occurrence while the sound is on joins it; a fire while the sound is off
 * starts the sound again, for a listed occurrence or a new one, and is a ring of its own. The sound stops when the
 * cap ends it, when she presses "Stop the sound", and when nothing listed is left: acting on an occurrence
 * (acknowledge, snooze or skip) takes it off the list and leaves the others ringing (ADR 0066), and an occurrence
 * that is no longer open is dropped (ADR 0090 item 7). An occurrence stays listed after the sound stops, until it is
 * acted on or dropped: the screen keeps showing what is due, with its actions, and so does the notification.
 *
 * **Spread** (ADR 0080 item 3): the moment a second occurrence is listed the session has spread, and every listed
 * occurrence has a notification of its own that carries its actions, while the session's notification carries none.
 * It stays spread until nothing is listed any more, whether or not the sound is on in between, so the buttons never
 * move back to the session's notification when one occurrence remains.
 *
 * This is state held in memory by the process. It holds no repository and no store type, so the ring screen,
 * which reads it, can be kept from reaching either (`verifyRingUiBoundary`).
 */
class RingSessions {
    private val lock = Any()
    private var sounding = false
    private var spread = false
    private val listed = mutableListOf<RingItem>()
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * How the session's notification was last posted, so that a change to what the session lists (an occurrence
     * acted on) can repost it the same way. Set by whoever posts it; the session only keeps it.
     */
    @Volatile
    var style: RingStyle? = null

    /** True while the session's sound is on. */
    val isActive: Boolean get() = synchronized(lock) { sounding }

    /**
     * True once a second occurrence has been listed, until nothing is listed (ADR 0080 item 3): each listed
     * occurrence's actions are on a notification of its own and the session's notification carries none.
     */
    val isSpread: Boolean get() = synchronized(lock) { spread }

    /** True if [occurrenceId] is listed and the sound is on: a fire for it continues the ring (ADR 0090 item 6). */
    fun isRinging(occurrenceId: String): Boolean =
        synchronized(lock) { sounding && listed.any { it.occurrenceId == occurrenceId } }

    /** What the screen lists: every occurrence that is due, whether or not the sound is on. */
    fun items(): List<RingItem> = synchronized(lock) { listed.toList() }

    fun join(item: RingItem): RingJoin {
        val result =
            synchronized(lock) {
                val index = listed.indexOfFirst { it.occurrenceId == item.occurrenceId }
                // Listed already, or listed now, with the freshest text and actions either way.
                if (index >= 0) listed[index] = item else listed += item
                if (listed.size > 1) spread = true
                when {
                    !sounding -> {
                        sounding = true
                        RingJoin.STARTED
                    }
                    index >= 0 -> RingJoin.CONTINUED
                    else -> RingJoin.JOINED
                }
            }
        changed()
        return result
    }

    /**
     * Takes [occurrenceId] off the list: she has acted on it, or it is no longer open, so it is no longer due. The
     * others keep ringing; if it was the last one listed while the sound was on, the ring ends with it.
     */
    fun resolve(occurrenceId: String): RingResolved {
        val result =
            synchronized(lock) {
                val wasListed = listed.any { it.occurrenceId == occurrenceId }
                listed.removeAll { it.occurrenceId == occurrenceId }
                val ended = sounding && listed.isEmpty()
                if (ended) sounding = false
                // The session is over when nothing is listed; only then can a later one start unspread.
                if (listed.isEmpty()) spread = false
                RingResolved(wasListed, listed.size, ended)
            }
        changed()
        return result
    }

    /** Replaces the actions of [occurrenceId] where it is listed, if it is: what the domain now says she may do. */
    fun refresh(
        occurrenceId: String,
        actions: Set<OccurrenceAction>,
    ) {
        synchronized(lock) {
            val index = listed.indexOfFirst { it.occurrenceId == occurrenceId }
            if (index >= 0) listed[index] = listed[index].copy(actions = actions)
        }
        changed()
    }

    /**
     * Replaces what is shown of [occurrenceId] where it is listed, if it is: its template's title, dosage and
     * instructions as they are now. They are display and change at once (ADR 0079 item 4); the actions stay.
     */
    fun describe(
        occurrenceId: String,
        title: String,
        dosage: String?,
        doctorInstructions: String?,
    ) {
        synchronized(lock) {
            val index = listed.indexOfFirst { it.occurrenceId == occurrenceId }
            if (index >= 0) {
                listed[index] =
                    listed[index].copy(title = title, dosage = dosage, doctorInstructions = doctorInstructions)
            }
        }
        changed()
    }

    /** Ends the ring: the sound is off. What is due stays listed until it is acted on or dropped. */
    fun end() {
        synchronized(lock) { sounding = false }
        changed()
    }

    fun addListener(listener: () -> Unit) {
        synchronized(lock) { listeners += listener }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    private fun changed() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { it() }
    }
}
