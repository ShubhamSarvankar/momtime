package com.momtime.android.debug

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.di.DeviceZone
import org.koin.core.context.GlobalContext
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.time.Clock

/**
 * The debug only seed screen (ADR 0072): one button that seeds a CRITICAL test reminder a few minutes out and three
 * daily STANDARD reminders, so that someone can test real alarms on a real device before Phase 3's schedule builder
 * exists. It is in the `debug` source set and declares itself as a launcher entry in the debug manifest, so it appears
 * in the launcher of a debug build and in no release build. Its text is not user facing and is not a resource.
 */
class SeedActivity : Activity() {
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.setPadding(PADDING, PADDING, PADDING, PADDING)
        column.addView(
            TextView(this).apply {
                setTextAppearance(android.R.style.TextAppearance_Material_Headline)
                text = "MomTime debug seed"
            },
        )
        column.addView(
            Button(this).apply {
                text = "Seed test reminders"
                setOnClickListener { seed() }
            },
        )
        result = TextView(this)
        column.addView(result)
        setContentView(
            ScrollView(this).apply {
                addView(
                    column,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            },
        )
    }

    private fun seed() {
        Executors.newSingleThreadExecutor().execute {
            val koin = GlobalContext.get()
            val seeder =
                Seeder(
                    koin.get(),
                    koin.get(),
                    koin.get(),
                    koin.get(),
                    koin.get<ArmingCoordinator>(),
                    koin.get<Clock>(),
                    { koin.get<DeviceZone>().current() },
                    { UUID.randomUUID().toString() },
                )
            val outcome = seeder.seed()
            runOnUiThread {
                result.text =
                    when (outcome) {
                        is SeedResult.Seeded -> "Seeded. ${outcome.occurrences} open occurrences; one alarm is armed."
                        SeedResult.AlreadySeeded -> "Already seeded. Nothing was changed."
                    }
            }
        }
    }

    private companion object {
        const val PADDING = 48
    }
}
