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
    /** How many occurrences are left in the session that is going. */
    val remaining: Int,
    /** True if this was the last one in a session that was going, so the session ended with it. */
    val ended: Boolean,
)

/** What joining the session did. */
enum class RingJoin {
    /** No session was going: this occurrence started one. */
    STARTED,

    /** The occurrence was already in the session: the ring in progress continues. */
    CONTINUED,

    /** A session was going and this occurrence was not in it: it joined, and no second session began. */
    JOINED,
}

/**
 * The ring session (ADR 0062): one at a time, covering every occurrence that is currently due. A fire for an
 * occurrence already in the session continues it (no restart, no second screen); a fire for another occurrence
 * joins it. The session ends when the sound is stopped, and when nothing in it is left unacknowledged: acting on
 * an occurrence (acknowledge, snooze or skip) takes it out of the session and leaves the others ringing
 * (ADR 0066). After it ends the screen still lists what was due, until the next session starts.
 *
 * This is state held in memory by the process. It holds no repository and no store type, so the ring screen,
 * which reads it, can be kept from reaching either (`verifyRingUiBoundary`).
 */
class RingSessions {
    private val lock = Any()
    private var active = false
    private val inSession = mutableListOf<RingItem>()
    private var shown: List<RingItem> = emptyList()
    private val listeners = mutableListOf<() -> Unit>()

    /**
     * How the session's notification was last posted, so that a change to what the session lists (an occurrence
     * acted on) can repost it the same way. Set by whoever posts it; the session only keeps it.
     */
    @Volatile
    var style: RingStyle? = null

    /** True if a ring session is going, which is to say the sound is playing for it. */
    val isActive: Boolean get() = synchronized(lock) { active }

    /** True if [occurrenceId] is in the session that is going. */
    fun isRinging(occurrenceId: String): Boolean =
        synchronized(lock) { active && inSession.any { it.occurrenceId == occurrenceId } }

    /** What the screen lists: the session's occurrences, or what they were when the last session ended. */
    fun items(): List<RingItem> = synchronized(lock) { shown }

    fun join(item: RingItem): RingJoin {
        val result =
            synchronized(lock) {
                val index = inSession.indexOfFirst { it.occurrenceId == item.occurrenceId }
                when {
                    !active -> {
                        active = true
                        inSession.clear()
                        inSession += item
                        shown = listOf(item)
                        RingJoin.STARTED
                    }
                    index >= 0 -> {
                        // The same occurrence at its next rung: the ring continues, with the freshest text.
                        inSession[index] = item
                        shown = inSession.toList()
                        RingJoin.CONTINUED
                    }
                    else -> {
                        inSession += item
                        shown = inSession.toList()
                        RingJoin.JOINED
                    }
                }
            }
        changed()
        return result
    }

    /**
     * Takes [occurrenceId] out of the session and off the screen's list: she has acted on it, so it is no longer
     * due. The others in the session keep ringing; if it was the last one the session ends with it.
     */
    fun resolve(occurrenceId: String): RingResolved {
        val result =
            synchronized(lock) {
                val listed = shown.any { it.occurrenceId == occurrenceId }
                inSession.removeAll { it.occurrenceId == occurrenceId }
                shown = shown.filterNot { it.occurrenceId == occurrenceId }
                val ended = active && inSession.isEmpty()
                if (ended) active = false
                RingResolved(listed, inSession.size, ended)
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
            val index = inSession.indexOfFirst { it.occurrenceId == occurrenceId }
            if (index >= 0) inSession[index] = inSession[index].copy(actions = actions)
            shown = shown.map { if (it.occurrenceId == occurrenceId) it.copy(actions = actions) else it }
        }
        changed()
    }

    /** Ends the session. The screen keeps listing what was due. */
    fun end() {
        synchronized(lock) {
            active = false
            inSession.clear()
        }
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
