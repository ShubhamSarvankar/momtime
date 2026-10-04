package com.momtime.android.ring

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.momtime.android.R

/** What the ring screen needs from the rest of the app, and nothing else. Set when the application starts. */
interface RingHost {
    val sessions: RingSessions

    /** Stops the sound. It writes nothing: stopping the sound is not completion (CLAUDE.md, ARCHITECTURE.md 4.6). */
    fun stopSound()
}

object RingEntryPoint {
    @Volatile
    var provider: (() -> RingHost)? = null
}

/**
 * The ring screen (ADR 0062). It is shown over the lock screen and turns the screen on, and it lists every
 * occurrence that is due, with its title, its dosage and her doctor's instructions exactly as she typed them.
 *
 * It never touches a repository, a store or an event: it reads the ring session, and its one action stops the
 * sound. Dismissing the alert is not completion, so stopping the sound writes nothing and the next rung still
 * fires. Acknowledge, snooze and skip are wired in PR 5b; until then this screen only stops the sound.
 *
 * It is also what the alarm clock's show intent opens, so with no session it says that nothing is ringing.
 * Phase 3 replaces that with the app's own screen.
 */
class RingActivity : Activity() {
    private var host: RingHost? = null
    private val listener: () -> Unit = { runOnUiThread(::render) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.ring)
        findViewById<Button>(R.id.ring_stop_sound).setOnClickListener { host?.stopSound() }
    }

    override fun onStart() {
        super.onStart()
        host = RingEntryPoint.provider?.invoke()
        host?.sessions?.addListener(listener)
        render()
    }

    override fun onStop() {
        host?.sessions?.removeListener(listener)
        super.onStop()
    }

    private fun render() {
        val sessions = host?.sessions
        val items = sessions?.items().orEmpty()
        val sounding = sessions?.isActive == true
        findViewById<TextView>(R.id.ring_count).text =
            if (items.isEmpty()) {
                getString(R.string.ring_nothing_due)
            } else {
                resources.getQuantityString(R.plurals.ring_due_count, items.size, items.size)
            }
        val container = findViewById<LinearLayout>(R.id.ring_items)
        container.removeAllViews()
        items.forEach { container.addView(itemView(it)) }
        findViewById<View>(R.id.ring_stop_sound).visibility = if (sounding) View.VISIBLE else View.GONE
        findViewById<View>(R.id.ring_sound_stopped).visibility =
            if (!sounding && items.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun itemView(item: RingItem): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ITEM_SPACING_PX
            }
        block.addView(text(item.title, android.R.style.TextAppearance_Material_Title))
        item.dosage?.let {
            block.addView(text(getString(R.string.ring_dosage_label), android.R.style.TextAppearance_Material_Caption))
            block.addView(text(it, android.R.style.TextAppearance_Material_Body1))
        }
        item.doctorInstructions?.let {
            block.addView(
                text(getString(R.string.ring_instructions_label), android.R.style.TextAppearance_Material_Caption),
            )
            block.addView(text(it, android.R.style.TextAppearance_Material_Body1))
        }
        return block
    }

    private fun text(
        value: String,
        style: Int,
    ) = TextView(this).apply {
        setTextAppearance(style)
        // The text is hers or ours and is shown as given; nothing here measures it or cuts it.
        text = value
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        private const val ITEM_SPACING_PX = 32

        /** Opens the ring screen from outside an activity: a notification, the show intent, the overlay route. */
        fun intent(context: Context): Intent =
            Intent(context, RingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
