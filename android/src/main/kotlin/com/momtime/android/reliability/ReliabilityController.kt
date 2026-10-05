package com.momtime.android.reliability

import com.momtime.android.settings.AndroidSettings
import com.momtime.android.store.CheckOutcome
import com.momtime.android.store.ReliabilityCheck
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.time.Clock
import kotlin.time.Instant

/** What the check screen needs from the rest of the app, and nothing else. Set when the application starts. */
interface ReliabilityHost {
    fun now(): Instant

    /** The report as of now. It reads the database, so the screen calls it off the main thread. */
    fun report(): ReliabilityReport

    fun startCheck(): StartResult

    /** Settles a check whose time is up, and returns what it settled, or null. */
    fun settleOverdue(): CheckOutcome?

    fun pendingCheck(): ReliabilityCheck?

    /** The report as the JSON document of [ReliabilityExport]. */
    fun exportJson(): String

    var shareOptIn: Boolean
}

object ReliabilityEntryPoint {
    @Volatile
    var provider: (() -> ReliabilityHost)? = null

    /** Where the screen runs what reads the database. A test replaces it with one that runs at once. */
    @Volatile
    var executor: Executor = Executors.newSingleThreadExecutor()
}

/** The host: the runner, the reader, her setting and the build, put together. */
class ReliabilityController internal constructor(
    private val runner: CanaryRunner,
    private val reader: ReliabilityReader,
    private val settings: AndroidSettings,
    private val device: DeviceInfo,
    private val clock: Clock,
) : ReliabilityHost {
    override fun now(): Instant = clock.now()

    override fun report(): ReliabilityReport = reader.read(clock.now())

    override fun startCheck(): StartResult = runner.start()

    override fun settleOverdue(): CheckOutcome? = runner.settleOverdue()

    override fun pendingCheck(): ReliabilityCheck? = runner.pending()

    override fun exportJson(): String = ReliabilityExport.toJson(report(), device)

    override var shareOptIn: Boolean
        get() = settings.shareReliabilityOptIn()
        set(value) = settings.setShareReliabilityOptIn(value)
}
