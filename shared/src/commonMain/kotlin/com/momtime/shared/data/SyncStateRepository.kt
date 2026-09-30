package com.momtime.shared.data

import com.momtime.shared.domain.SyncState

interface SyncStateRepository {
    fun ensureSeeded()

    fun current(): SyncState

    fun update(state: SyncState)
}

class SqlDelightSyncStateRepository(
    private val database: MomTimeDatabase,
) : SyncStateRepository {
    override fun ensureSeeded() {
        database.syncStateQueries.seedSyncState()
    }

    override fun current(): SyncState {
        ensureSeeded()
        val row = database.syncStateQueries.selectSyncState().executeAsOne()
        return SyncState(
            occurrencesSyncedThrough = row.occurrences_synced_through.toInstantOrNull(),
            lastSyncAt = row.last_sync_at.toInstantOrNull(),
        )
    }

    override fun update(state: SyncState) {
        database.syncStateQueries.updateSyncState(
            state.occurrencesSyncedThrough.toDbOrNull(),
            state.lastSyncAt.toDbOrNull(),
        )
    }
}
