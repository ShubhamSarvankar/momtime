package com.momtime.android.data

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.momtime.android.di.AndroidDatabaseDriverFactory
import com.momtime.android.di.AndroidStoreDriverFactory
import com.momtime.android.di.ProcessEnd
import com.momtime.android.di.StoreDriverFactory
import com.momtime.android.di.momTimeModules
import com.momtime.shared.data.DatabaseDriverFactory
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.robolectric.annotation.SQLiteMode
import org.robolectric.config.ConfigurationRegistry
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Every data test pins Robolectric's SQLite to NATIVE and asserts it. In LEGACY mode the framework's
 * connection and locking code is replaced by a different implementation, so a test of waiting
 * against a lock would be a test of the simulation. A test class that silently fell back would
 * still pass its own assertions, so each one calls this first.
 */
internal fun assertNativeSqliteMode() {
    assertEquals(SQLiteMode.Mode.NATIVE, ConfigurationRegistry.get(SQLiteMode.Mode::class.java))
}

/** Counts the drivers a factory creates and remembers them, so a test can close them. */
internal class TrackingFactory(
    private val delegate: DatabaseDriverFactory,
) : DatabaseDriverFactory {
    private val drivers = mutableListOf<SqlDriver>()

    val created: Int get() = synchronized(drivers) { drivers.size }

    override fun createDriver(): SqlDriver =
        delegate.createDriver().also { driver -> synchronized(drivers) { drivers.add(driver) } }

    fun closeAll() = synchronized(drivers) { drivers.forEach { runCatching { it.close() } } }
}

/** A driver that records that it was closed, so a test can count the drivers that are still live. */
internal class ClosableDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    @Volatile
    var closed = false
        private set

    override fun close() {
        closed = true
        delegate.close()
    }
}

/**
 * The same for the android store's driver factory, and it counts live drivers: the store may be
 * reopened, but there must be exactly one live driver at any time (ADR 0051).
 */
internal class TrackingStoreFactory(
    private val delegate: StoreDriverFactory,
) : StoreDriverFactory {
    private val drivers = mutableListOf<ClosableDriver>()

    val created: Int get() = synchronized(drivers) { drivers.size }
    val live: Int get() = synchronized(drivers) { drivers.count { !it.closed } }

    override fun createDriver(onClosedByCorruption: () -> Unit): SqlDriver =
        ClosableDriver(delegate.createDriver(onClosedByCorruption)).also { synchronized(drivers) { drivers.add(it) } }

    fun closeAll() = synchronized(drivers) { drivers.forEach { runCatching { it.close() } } }
}

/**
 * Stands in for ending the process. It records each call and runs [onEnd] at the instant of the call, so
 * a test can look at what is true then (ADR 0051).
 */
internal class RecordingProcessEnd(
    private val onEnd: () -> Unit = {},
) : ProcessEnd {
    @Volatile
    var calls = 0
        private set

    override fun end() {
        calls++
        onEnd()
    }
}

/**
 * A Koin application of the production modules over a shared database file named [name] and an
 * android store file named [storeName]. Ending the process is replaced by [processEnd].
 */
internal class TestGraph(
    context: Context,
    val name: String = "t-${UUID.randomUUID().toString().take(8)}.db",
    clock: Clock = testClock,
    val storeName: String = "s-${UUID.randomUUID().toString().take(8)}.db",
    val processEnd: RecordingProcessEnd = RecordingProcessEnd(),
) {
    // Robolectric does not always create the databases directory the way a device does when the
    // framework asks for a database path, so the test makes it.
    init {
        context.getDatabasePath(name).parentFile?.mkdirs()
    }

    val factory = TrackingFactory(AndroidDatabaseDriverFactory(context, name, clock, processEnd))
    val storeFactory = TrackingStoreFactory(AndroidStoreDriverFactory(context, storeName, clock))
    private val application: KoinApplication =
        koinApplication { modules(momTimeModules(context, factory, storeFactory)) }
    val koin get() = application.koin

    inline fun <reified T : Any> get(): T = koin.get()

    fun close() {
        application.close()
        factory.closeAll()
        storeFactory.closeAll()
    }
}

internal fun SqlDriver.pragma(name: String): String =
    executeQuery(
        identifier = null,
        sql = "PRAGMA $name",
        mapper = { cursor ->
            check(cursor.next().value) { "PRAGMA $name returned no row" }
            QueryResult.Value(cursor.getString(0) ?: cursor.getLong(0).toString())
        },
        parameters = 0,
    ).value

/** Rows `PRAGMA foreign_key_check` reports as violating a declared foreign key. Empty means none. */
internal fun SqlDriver.foreignKeyViolations(): List<String> =
    executeQuery(
        identifier = null,
        sql = "PRAGMA foreign_key_check",
        mapper = { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) {
                rows += "${cursor.getString(0)} rowid=${cursor.getLong(1)} -> ${cursor.getString(2)}"
            }
            QueryResult.Value(rows.toList())
        },
        parameters = 0,
    ).value

internal val testZone: TimeZone = TimeZone.of("Asia/Kolkata")
internal val epoch: Instant = Instant.fromEpochMilliseconds(0)

/** A fixed clock. Tests never read the wall clock (invariant 8). */
internal val testClock: Clock =
    object : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

internal fun pregnancy(id: String = "preg-1") = Pregnancy(id, PregnancyPhase.PRENATAL, epoch, epoch)

internal fun template(
    id: String = "tmpl-1",
    pregnancyId: String = "preg-1",
) = ScheduleTemplate(
    id = id,
    pregnancyId = pregnancyId,
    title = "Iron tablet",
    notes = null,
    taskType = TaskType.SUPPLEMENT,
    criticality = Criticality.CRITICAL,
    timeOfDay = LocalTime(8, 0),
    timeZoneId = testZone,
    recurrence = Recurrence.Daily,
    mission = MissionConfig.None,
    nutritionTags = setOf(NutritionTag.IRON),
    dosage = null,
    doctorInstructions = null,
    inventoryCount = null,
    refillThresholdDays = null,
    active = true,
)

/** A pregnancy and one template, inserted through the repositories. Returns the template. */
internal fun TestGraph.seedTemplate(): ScheduleTemplate {
    get<PregnancyRepository>().insert(pregnancy())
    val template = template()
    get<ScheduleTemplateRepository>().insert(template)
    return template
}
