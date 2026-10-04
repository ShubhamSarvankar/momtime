package com.momtime.shared.engine

import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class AlarmEventsTest {
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `scheduled, fired and repair are system events with no payload and no effective time`() {
        assertEquals(
            Event("e1", "occ", EventType.ALARM_SCHEDULED, now, null, EventSource.SYSTEM, EventPayload.None),
            AlarmEvents.scheduled("e1", "occ", now),
        )
        assertEquals(
            Event("e2", "occ", EventType.ALARM_FIRED, now, null, EventSource.SYSTEM, EventPayload.None),
            AlarmEvents.fired("e2", "occ", now),
        )
        assertEquals(
            Event("e3", "occ", EventType.WATCHDOG_REPAIR, now, null, EventSource.SYSTEM, EventPayload.None),
            AlarmEvents.watchdogRepair("e3", "occ", now),
        )
    }

    // A snooze is not a rung: its end is its own event, so the count of ALARM_FIRED stays the count of rungs.
    @Test
    fun `a snooze ending is a system event of its own and not an ALARM_FIRED`() {
        assertEquals(
            Event("e4", "occ", EventType.SNOOZE_ENDED, now, null, EventSource.SYSTEM, EventPayload.None),
            AlarmEvents.snoozeEnded("e4", "occ", now),
        )
    }
}
