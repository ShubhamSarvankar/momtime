package com.momtime.android.arming

import android.content.Context
import com.momtime.android.data.template
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.ScheduleTemplateRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import kotlin.time.Duration.Companion.hours

/**
 * Auto Backup restore (ARCHITECTURE.md section 5.4, decision f of Phase 2 Step 1). A restored phone has the
 * shared database, with its occurrences and its slot counter, and nothing else: the android store is not
 * backed up, alarms are system state, and the app is stopped, so the first code to run is the next launch.
 *
 * The shared database file of a populated install is copied into a fresh install, which has no armed record
 * and no alarms, and the start path runs. It must arm from the restored occurrences, and slot allocation must
 * go on from the restored counter, because a slot handed out twice is a request code that cancels another
 * alarm (invariant 10). What a real backup agent copies is `MANUAL_CHECKS.md` P2-7.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class RestoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val source = ArmingFixture(context, idPrefix = "old")
    private var restored: ArmingFixture? = null

    @After
    fun tearDown() {
        source.close()
        restored?.close()
    }

    @Test
    fun `a restored install arms and goes on from the restored slot counter`() {
        source.graph.get<ScheduleTemplateRepository>().insert(template("daily"))
        source.clock.now = t0
        val created = source.graph.get<MaterialiseCommand>().dispatch(t0)
        val restoredSlots =
            source.occurrences
                .findForTemplate("daily")
                .map { it.alarmSlot }
                .sorted()
        assertEquals(2, created)
        assertEquals(listOf(1, 2), restoredSlots)
        // Copy the database file as it is, then close the source, as if backed up and restored elsewhere.
        val name = source.graph.name
        val file: File = context.getDatabasePath(name)
        val copy = File(file.parentFile, "restored-$name")
        file.copyTo(copy, overwrite = true)
        source.close()

        // A fresh install: the shared database restored, no armed record, no alarm.
        val fresh = ArmingFixture(context, name = copy.name, idPrefix = "new", seedPregnancy = false)
        restored = fresh
        fresh.clock.now = t0
        assertNull("the android store is not restored", fresh.armed.current())
        assertEquals("and no alarm is armed", 0, fresh.alarms().size)

        val result = fresh.appStart.run()

        assertTrue("the start path must arm from the restored occurrences: $result", result is EnsureResult.Armed)
        val first = fresh.occurrences.findForTemplate("daily").minBy { it.scheduledInstant }
        assertEquals(first.scheduledInstant.toEpochMilliseconds(), fresh.alarms().single().triggerAtMs)
        assertEquals(first.alarmSlot, fresh.requestCode(fresh.alarms().single()))
        assertNotNull(fresh.armed.current())

        // A day later the window reaches a new date. Its slot must come after the restored ones.
        fresh.clock.now = t0 + 24.hours
        val next = runCatching { fresh.graph.get<MaterialiseCommand>().dispatch(fresh.clock.now) }
        assertTrue("allocating after a restore collided: ${next.exceptionOrNull()}", next.isSuccess)
        assertEquals(1, next.getOrThrow())
        val slots = fresh.occurrences.findForTemplate("daily").map { it.alarmSlot }
        assertEquals("every slot is distinct", slots.size, slots.toSet().size)
        assertTrue("the new slot continues the counter", slots.max() > restoredSlots.max())
    }
}
