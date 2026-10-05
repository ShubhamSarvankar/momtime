package com.momtime.android.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.reliability.ReliabilityCheckActivity

/**
 * The Samsung One UI walkthrough (ADR 0072), shown after the permissions when the phone is a Samsung: four steps, each
 * a caption and a screenshot of what to look for, and a button that opens it. A deep link that cannot be started falls
 * back to the general settings and says so beside the screenshot. Then the reliability check. The screenshots are
 * placeholders until a device supplies them (`MANUAL_CHECKS.md` P2-34 to P2-37).
 */
class SamsungStepsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.samsung_steps)
        val container = findViewById<LinearLayout>(R.id.samsung_steps)
        SamsungStep.entries.forEach { container.addView(block(it)) }
        findViewById<Button>(R.id.samsung_continue).setOnClickListener {
            startActivity(ReliabilityCheckActivity.intent(this))
        }
    }

    private fun block(step: SamsungStep): LinearLayout {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        block.layoutParams = params().apply { topMargin = BLOCK_SPACING_PX }
        block.tag = step.name
        block.addView(text(step.title, android.R.style.TextAppearance_Material_Subhead))
        block.addView(text(step.caption, android.R.style.TextAppearance_Material_Body1))
        block.addView(
            ImageView(this).apply {
                setImageResource(step.screenshot)
                adjustViewBounds = true
                contentDescription = getString(step.caption)
                layoutParams = params()
            },
        )
        val hint =
            text(R.string.samsung_fallback_hint, android.R.style.TextAppearance_Material_Caption).apply {
                visibility = View.GONE
                tag = "hint:${step.name}"
            }
        block.addView(
            Button(this).apply {
                setText(R.string.samsung_open)
                tag = "open:${step.name}"
                layoutParams = params()
                setOnClickListener {
                    hint.visibility =
                        if (step.open(this@SamsungStepsActivity) == StepOpened.DEEP_LINK) View.GONE else View.VISIBLE
                }
            },
        )
        block.addView(hint)
        return block
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
        private const val BLOCK_SPACING_PX = 48

        fun intent(context: Context): Intent =
            Intent(context, SamsungStepsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
