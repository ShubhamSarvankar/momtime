package com.momtime.android.onboarding

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ResolveInfo
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.reliability.ReliabilityCheckActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Samsung walkthrough (ADR 0072): four steps, each with a caption and a screenshot, each opening its deep link or,
 * if that cannot be started, the general settings, and never throwing. Every component name is unverified until a
 * device check (`MANUAL_CHECKS.md` P2-34 to P2-37); these tests show the fallback works, which is what makes an
 * unverified name safe, and not that Samsung's Device Care has any such activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class SamsungStepsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** A context whose `startActivity` throws what [refuses] says, and records the rest. */
    private class RefusingContext(
        base: Context,
        private val refuses: (Intent) -> Throwable?,
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            refuses(intent)?.let { throw it }
            started += intent
        }
    }

    private fun isDeepLink(intent: Intent) = intent.component?.packageName == "com.samsung.android.lool"

    @Test
    fun `a deep link that starts is the step opened`() {
        val ctx = RefusingContext(context) { null }

        assertEquals(StepOpened.DEEP_LINK, SamsungStep.SLEEPING_APPS.open(ctx))

        assertEquals(SamsungStep.SLEEPING_APPS.deepLink, ctx.started.single().component)
    }

    @Test
    fun `a deep link that cannot be found falls back to the general settings`() {
        for (step in SamsungStep.entries) {
            val ctx =
                RefusingContext(context) { if (isDeepLink(it)) ActivityNotFoundException("no such activity") else null }

            assertEquals(step.name, StepOpened.FALLBACK, step.open(ctx))

            assertEquals(step.fallbackAction, ctx.started.single().action)
        }
    }

    @Test
    fun `a deep link that is not exported falls back too`() {
        val ctx = RefusingContext(context) { if (isDeepLink(it)) SecurityException("Permission Denial") else null }

        assertEquals(StepOpened.FALLBACK, SamsungStep.DEEP_SLEEPING_APPS.open(ctx))
        assertEquals(SamsungStep.DEEP_SLEEPING_APPS.fallbackAction, ctx.started.single().action)
    }

    @Test
    fun `when nothing can be started nothing opens and nothing is thrown`() {
        val ctx = RefusingContext(context) { ActivityNotFoundException() }

        assertEquals(StepOpened.NOTHING, SamsungStep.UNUSED_APPS.open(ctx))
        assertEquals(emptyList<Intent>(), ctx.started)
    }

    @Test
    fun `the four steps are the battery setting and the three sleep lists, each with a deep link and a screenshot`() {
        assertEquals(
            listOf("BATTERY", "SLEEPING_APPS", "DEEP_SLEEPING_APPS", "UNUSED_APPS"),
            SamsungStep.entries.map { it.name },
        )
        assertEquals(
            "every deep link is its own",
            SamsungStep.entries.size,
            SamsungStep.entries
                .map {
                    it.deepLink
                }.toSet()
                .size,
        )
        for (step in SamsungStep.entries) {
            assertEquals("Device Care", "com.samsung.android.lool", step.deepLink.packageName)
            assertTrue(context.getString(step.title).isNotBlank())
            assertTrue(context.getString(step.caption).isNotBlank())
            assertNotNull(context.getDrawable(step.screenshot))
        }
        assertEquals(SETTINGS_IGNORE, SamsungStep.BATTERY.fallbackAction)
    }

    // The placeholder drawables have fixed names, which the pull request lists so that real screenshots can replace
    // them without a code change. Renaming one would break that contract.
    @Test
    fun `the screenshots have the fixed names a real screenshot will take`() {
        val names =
            listOf(
                "samsung_step_battery",
                "samsung_step_sleeping_apps",
                "samsung_step_deep_sleeping_apps",
                "samsung_step_unused_apps",
            )
        for (name in names) {
            val id = context.resources.getIdentifier(name, "drawable", context.packageName)
            assertNotEquals("drawable $name must exist", 0, id)
        }
        assertEquals(names, SamsungStep.entries.map { context.resources.getResourceEntryName(it.screenshot) })
    }

    @Test
    fun `the screen lists every step with caption, screenshot and open button, and a hint hidden until a fallback`() {
        val activity =
            Robolectric
                .buildActivity(SamsungStepsActivity::class.java)
                .create()
                .start()
                .resume()
                .get()

        for (step in SamsungStep.entries) {
            val block = activity.findViewById<View>(R.id.samsung_steps).findViewWithTag<View>(step.name)
            assertNotNull(step.name, block)
            assertNotNull(block.findViewWithTag<Button>("open:${step.name}"))
            assertEquals(View.GONE, block.findViewWithTag<TextView>("hint:${step.name}").visibility)
            assertEquals(
                1,
                (block as android.view.ViewGroup).let { g ->
                    (0 until g.childCount).count { g.getChildAt(it) is ImageView }
                },
            )
        }
    }

    @Test
    fun `opening a step on the screen starts its deep link and shows no hint`() {
        val activity =
            Robolectric
                .buildActivity(SamsungStepsActivity::class.java)
                .create()
                .start()
                .resume()
                .get()

        activity.findViewById<View>(R.id.samsung_steps).findViewWithTag<Button>("open:BATTERY").performClick()

        assertEquals(SamsungStep.BATTERY.deepLink, shadowOf(activity).nextStartedActivity.component)
        assertEquals(
            View.GONE,
            activity.findViewById<View>(R.id.samsung_steps).findViewWithTag<TextView>("hint:BATTERY").visibility,
        )
    }

    @Test
    fun `a deep link the phone cannot start falls back to the general settings and the hint says so`() {
        shadowOf(context as android.app.Application).checkActivities(true)
        val fallback = SamsungStep.SLEEPING_APPS.fallbackIntent()
        shadowOf(context.packageManager).addResolveInfoForIntent(fallback, ResolveInfo())
        val activity =
            Robolectric
                .buildActivity(SamsungStepsActivity::class.java)
                .create()
                .start()
                .resume()
                .get()

        activity.findViewById<View>(R.id.samsung_steps).findViewWithTag<Button>("open:SLEEPING_APPS").performClick()

        assertEquals(SamsungStep.SLEEPING_APPS.fallbackAction, shadowOf(activity).nextStartedActivity.action)
        assertEquals(
            View.VISIBLE,
            activity.findViewById<View>(R.id.samsung_steps).findViewWithTag<TextView>("hint:SLEEPING_APPS").visibility,
        )
    }

    @Test
    fun `continuing goes to the reliability check`() {
        val activity =
            Robolectric
                .buildActivity(SamsungStepsActivity::class.java)
                .create()
                .start()
                .resume()
                .get()

        activity.findViewById<Button>(R.id.samsung_continue).performClick()

        assertEquals(
            ComponentName(context, ReliabilityCheckActivity::class.java),
            shadowOf(activity).nextStartedActivity.component,
        )
    }

    private companion object {
        const val SETTINGS_IGNORE = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
    }
}
