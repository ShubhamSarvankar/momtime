package com.momtime.android.data

import android.database.sqlite.SQLiteDatabase
import com.momtime.android.store.ArmedAlarm
import com.momtime.android.store.ArmedAlarmRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import kotlin.time.Instant

/**
 * The android store's forward migration from version 1 to version 2 (ADR 0048, ADR 0058): the armed record gains
 * the app's version code. The starting point is the committed baseline snapshot (`databases/1.db`), the schema a
 * version 1 install holds, never a regenerated one (ADR 0035). The framework migrates from `user_version`.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AndroidStoreMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    private fun v1Store(name: String) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        File("src/main/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.version = 1
            db.execSQL(
                "INSERT INTO armed_alarm(id, alarm_slot, rung_instant, armed_at, boot_count, exact_allowed) " +
                    "VALUES (0, 7, 1000, 900, 3, 1)",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a version 1 store migrates to 2 and keeps its armed record`() {
        val name = "s-v1.db"
        v1Store(name)

        val graph = TestGraph(context, storeName = name).also { graphs.add(it) }
        val repo = graph.get<ArmedAlarmRepository>()
        val kept = checkNotNull(repo.current())

        assertEquals(7, kept.alarmSlot)
        assertEquals(Instant.fromEpochMilliseconds(1000), kept.rungInstant)
        assertEquals(Instant.fromEpochMilliseconds(900), kept.armedAt)
        assertEquals(3L, kept.bootCount)
        assertEquals(true, kept.exactAllowed)
        assertEquals("a record from before the version was kept reads as version 0", 0L, kept.versionCode)
        val driver = graph.storeFactory.createDriver {}
        assertEquals("2", driver.pragma("user_version"))
        assertEquals("1", driver.pragma("foreign_keys"))

        // The migrated table takes the new column.
        assertEquals(true, repo.replace(kept.copy(versionCode = 123)))
        assertEquals(123L, repo.current()?.versionCode)
    }

    @Test
    fun `a new store is created at version 2`() {
        val graph = TestGraph(context, storeName = "s-new.db").also { graphs.add(it) }
        val repo = graph.get<ArmedAlarmRepository>()
        val record =
            ArmedAlarm(
                alarmSlot = 1,
                rungInstant = Instant.fromEpochMilliseconds(5),
                armedAt = Instant.fromEpochMilliseconds(4),
                bootCount = 1,
                exactAllowed = false,
                versionCode = 9,
            )
        assertEquals(true, repo.replace(record))
        assertEquals(record, repo.current())
        assertEquals("2", graph.storeFactory.createDriver {}.pragma("user_version"))
    }
}
