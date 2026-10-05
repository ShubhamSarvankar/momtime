package com.momtime.android.reliability

import android.content.Context
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.t0
import com.momtime.android.store.BootInstant
import com.momtime.android.store.BootInstantRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * When each boot began (ADR 0070): the wall clock now minus the time since boot, recorded under the boot count the
 * first time the app runs in a boot, by the boot pass or by the app starting, whichever gets there first. A rung that
 * never fired is excused only if the first boot after it began after the end of its grace, so these instants are what
 * the excuse stands on.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class BootInstantTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val boots: BootInstantRepository get() = fixture.graph.get()

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `the app running records when the boot began, as now minus the time since boot`() {
        fixture.bootCount = 7
        fixture.uptime = 2.hours
        fixture.clock.now = t0

        fixture.appStart.run()

        assertEquals(listOf(BootInstant(7, t0 - 2.hours)), boots.all())
    }

    @Test
    fun `the first answer for a boot is kept, and a new boot is a new row, earliest first`() {
        fixture.bootCount = 7
        fixture.uptime = 2.hours
        fixture.clock.now = t0
        fixture.appStart.run()

        // Later in the same boot the sum differs by the time between the runs only if the clock was set; the first
        // answer stays.
        fixture.uptime = 3.hours
        fixture.clock.now = t0 + 30.minutes
        fixture.appStart.run()
        assertEquals(listOf(BootInstant(7, t0 - 2.hours)), boots.all())

        fixture.bootCount = 8
        fixture.uptime = 20.minutes
        fixture.clock.now = t0 + 5.hours
        fixture.appStart.run()
        assertEquals(
            listOf(BootInstant(7, t0 - 2.hours), BootInstant(8, t0 + 5.hours - 20.minutes)),
            boots.all(),
        )
    }

    @Test
    fun `a boot count the platform does not report records nothing`() {
        fixture.bootCount = -1
        fixture.uptime = 2.hours

        fixture.appStart.run()

        assertEquals(emptyList<BootInstant>(), boots.all())
    }

    @Test
    fun `the repository keeps the first answer per boot and lists earliest first`() {
        assertEquals(true, boots.record(BootInstant(9, t0)))
        assertEquals(true, boots.record(BootInstant(9, t0 + 1.hours)))
        assertEquals(true, boots.record(BootInstant(8, t0 - 1.hours)))

        assertEquals(listOf(BootInstant(8, t0 - 1.hours), BootInstant(9, t0)), boots.all())
    }
}
