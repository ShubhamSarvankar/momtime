package com.momtime.android.ring

/**
 * One occurrence as the ring screen shows it. [title], [dosage] and [doctorInstructions] are hers, shown exactly
 * as she typed them and never parsed (CLAUDE.md). Only fields that exist in the model are here.
 */
data class RingItem(
    val occurrenceId: String,
    val title: String,
    val dosage: String?,
    val doctorInstructions: String?,
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
 * joins it. The session ends when the sound is stopped, and in PR 5b also when nothing in it is left
 * unacknowledged. After it ends the screen still lists what was due, until the next session starts.
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
