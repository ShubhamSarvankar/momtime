package com.momtime.android.reliability

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.store.ReliabilityCheck
import java.io.IOException
import kotlin.time.Instant

/**
 * The reliability check screen (ADR 0069, ADR 0070), reached from the ring screen's idle state. She starts the test
 * alarm, watches it, and reads how her reminders have arrived: the last test, how late reminders have been by tier, and
 * the counts. It also holds the opt in and the export. It is Phase 2's only home for any of this; Phase 3 gives the
 * reliability view its place in the app and keeps the runner, the report and the export.
 *
 * It never touches a repository, a store or an event: it reads and acts through the [ReliabilityHost], off the main
 * thread, as the ring screen does. The text is the report as plain sentences, with plurals, and every view wraps.
 */
class ReliabilityCheckActivity : Activity() {
    private var host: ReliabilityHost? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reliability)
        findViewById<Button>(R.id.check_start).setOnClickListener { start() }
        findViewById<Button>(R.id.check_export).setOnClickListener { chooseExportFile() }
    }

    override fun onStart() {
        super.onStart()
        host = ReliabilityEntryPoint.provider?.invoke()
        val optIn = findViewById<CheckBox>(R.id.check_opt_in)
        optIn.setOnCheckedChangeListener(null)
        optIn.isChecked = host?.shareOptIn == true
        optIn.setOnCheckedChangeListener { _, checked -> host?.shareOptIn = checked }
        refresh()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    private fun start() {
        val target = host ?: return
        ReliabilityEntryPoint.executor.execute {
            val result = target.startCheck()
            runOnUiThread {
                when (result) {
                    is StartResult.Started -> refresh()
                    StartResult.AlreadyRunning -> status(getString(R.string.check_already_running))
                    StartResult.ReminderNear -> status(getString(R.string.check_start_near))
                    StartResult.Failed -> status(getString(R.string.check_start_failed))
                }
            }
        }
    }

    /** Settles a check whose time is up, reads the report and shows both; again in a second while one runs. */
    private fun refresh() {
        val target = host ?: return
        handler.removeCallbacks(tick)
        ReliabilityEntryPoint.executor.execute {
            target.settleOverdue()
            val pending = target.pendingCheck()
            val report = target.report()
            val now = target.now()
            runOnUiThread {
                render(pending, report, now)
                if (pending != null) handler.postDelayed(tick, TICK_MILLIS)
            }
        }
    }

    private fun render(
        pending: ReliabilityCheck?,
        report: ReliabilityReport,
        now: Instant,
    ) {
        status(ReliabilityText.status(resources, pending, report.checks.firstOrNull(), now))
        findViewById<TextView>(R.id.check_report).text = ReliabilityText.report(resources, report)
    }

    private fun status(text: String) {
        findViewById<TextView>(R.id.check_status).text = text
    }

    private fun chooseExportFile() {
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(ReliabilityExport.MIME_TYPE)
                .putExtra(Intent.EXTRA_TITLE, ReliabilityExport.FILE_NAME)
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Deprecated("The result of the document picker; the framework call is the simplest that works on API 29.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data?.takeIf { requestCode == REQUEST_EXPORT && resultCode == RESULT_OK }
        val target = host
        if (uri != null && target != null) writeExport(uri, target)
    }

    private fun writeExport(
        uri: Uri,
        target: ReliabilityHost,
    ) {
        ReliabilityEntryPoint.executor.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(target.exportJson().toByteArray(Charsets.UTF_8)) }
            } catch (e: IOException) {
                Log.e(TAG, "export failed: ${e.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "MomTimeCheck"
        private const val REQUEST_EXPORT = 7
        private const val TICK_MILLIS = 1_000L

        fun intent(context: Context): Intent =
            Intent(context, ReliabilityCheckActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
