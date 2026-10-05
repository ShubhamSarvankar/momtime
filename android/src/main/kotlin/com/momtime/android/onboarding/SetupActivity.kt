package com.momtime.android.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.reliability.ReliabilityCheckActivity

/**
 * The permission screen (ADR 0072), the first step of the setup that Phase 3's onboarding will replace: the ring
 * screen's idle state links here, then to the Samsung steps when the phone is a Samsung, then to the reliability check.
 *
 * It lists each flow with whether it is needed and a button that launches it. Every time she comes back to it (from a
 * settings screen, or from the notification dialog) it asks the host to resolve capability again and call
 * `ensureArmed`,
 * so a change she just made takes effect at once and the list shows what is still missing. It never touches a
 * repository, a store or an event: it reads and acts through the [SetupHost], off the main thread.
 */
class SetupActivity : Activity() {
    private var host: SetupHost? = null
    private var samsung = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.setup)
        findViewById<Button>(R.id.setup_continue).setOnClickListener { startActivity(next()) }
    }

    override fun onResume() {
        super.onResume()
        host = SetupEntryPoint.provider?.invoke()
        refresh()
    }

    @Deprecated("The result of the permission dialog; the framework call is the simplest that works on API 29.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val target = host ?: return
        SetupEntryPoint.executor.execute {
            val state = target.onReturn()
            runOnUiThread { render(state) }
        }
    }

    private fun next(): Intent =
        if (samsung) SamsungStepsActivity.intent(this) else ReliabilityCheckActivity.intent(this)

    private fun render(state: SetupState) {
        samsung = state.samsung
        findViewById<TextView>(R.id.setup_tier).text = getString(SetupText.tier(state))
        val container = findViewById<LinearLayout>(R.id.setup_items)
        container.removeAllViews()
        state.items.forEach { container.addView(row(it)) }
    }

    private fun row(item: FlowItem): LinearLayout {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.layoutParams = params().apply { topMargin = ITEM_SPACING_PX }
        block.tag = item.flow.name
        block.addView(text(SetupText.title(item.flow), android.R.style.TextAppearance_Material_Subhead))
        block.addView(text(SetupText.status(item), android.R.style.TextAppearance_Material_Body1))
        val launch = item.launch
        if (item.needed && launch != null) {
            block.addView(
                Button(this).apply {
                    setText(R.string.setup_allow)
                    tag = "allow:${item.flow.name}"
                    layoutParams = params()
                    setOnClickListener { launch(launch) }
                },
            )
        }
        return block
    }

    private fun launch(launch: FlowLaunch) {
        when (launch) {
            is FlowLaunch.RuntimeRequest -> {
                host?.markNotificationsRequested()
                requestPermissions(arrayOf(launch.permission), REQUEST_PERMISSION)
            }
            is FlowLaunch.OpenSettings -> SetupText.openSettings(this, launch.intent)
        }
    }

    private fun text(
        resource: Int,
        style: Int,
    ) = TextView(this).apply {
        setTextAppearance(style)
        setText(resource)
        layoutParams = params()
    }

    private fun params() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        private const val ITEM_SPACING_PX = 32
        private const val REQUEST_PERMISSION = 8

        fun intent(context: Context): Intent =
            Intent(context, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
