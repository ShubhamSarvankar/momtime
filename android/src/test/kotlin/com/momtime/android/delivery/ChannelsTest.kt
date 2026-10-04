package com.momtime.android.delivery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import com.momtime.android.arming.FiredRung
import com.momtime.android.arming.Presentation
import com.momtime.android.capability.PlatformCapabilityReader
import com.momtime.android.capability.ResolvedTier
import com.momtime.android.capability.resolveDelivery
import com.momtime.android.ringer.AlarmAudio
import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.engine.RungDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSettings
import kotlin.time.Instant

/**
 * The notification channels (ADR 0060), the Critical channel input to capability resolution (progress decision 26,
 * ADR 0050), the delivery path as a function, and the alarm audio attributes (ADR 0061), at SDK 29, 31, 33, 34
 * and 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 31, 33, 34, 36])
class ChannelsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(manager).setNotificationsEnabled(true)
        ShadowSettings.setCanDrawOverlays(false)
    }

    // Split by criticality, never by task type, plus one for what arrives silently (ADR 0064). Critical and Standard
    // are importance high, because a heads up and a full screen intent need it; Gentle and Quiet notices are low.
    @Test
    fun `there are four channels, three by criticality and one for silent notices`() {
        NotificationChannels.ensure(context)

        fun channel(id: String): NotificationChannel = checkNotNull(manager.getNotificationChannel(id))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel(NotificationChannels.CRITICAL).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel(NotificationChannels.STANDARD).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel(NotificationChannels.GENTLE).importance)
        assertNull("Gentle makes no sound of its own", channel(NotificationChannels.GENTLE).sound)
        assertEquals(
            "Critical sounds on the alarm stream",
            AudioAttributes.USAGE_ALARM,
            channel(NotificationChannels.CRITICAL).audioAttributes.usage,
        )
        assertEquals(
            "Quiet notices is importance low: no sound, no heads up",
            NotificationManager.IMPORTANCE_LOW,
            channel(NotificationChannels.QUIET).importance,
        )
        assertNull("Quiet notices makes no sound of its own", channel(NotificationChannels.QUIET).sound)
        assertEquals(4, manager.notificationChannels.size)
    }

    @Test
    fun `quiet notices is not gentle`() {
        assertTrue(NotificationChannels.QUIET != NotificationChannels.GENTLE)
        assertTrue(
            "no criticality is presented on the quiet channel",
            Criticality.entries.none { NotificationChannels.idFor(it) == NotificationChannels.QUIET },
        )
    }

    @Test
    fun `each criticality maps to its own channel`() {
        assertEquals(NotificationChannels.CRITICAL, NotificationChannels.idFor(Criticality.CRITICAL))
        assertEquals(NotificationChannels.STANDARD, NotificationChannels.idFor(Criticality.STANDARD))
        assertEquals(NotificationChannels.GENTLE, NotificationChannels.idFor(Criticality.GENTLE))
        assertEquals(
            Criticality.entries.size,
            Criticality.entries
                .map(NotificationChannels::idFor)
                .toSet()
                .size,
        )
    }

    // The Critical channel input (decision 26): a user can block that one channel while notifications stay on, and
    // critical delivery then must not count as full screen. A channel that does not exist yet is not blocked.
    @Test
    fun `a blocked critical channel fails tier 3`() {
        val reader = PlatformCapabilityReader(context, fullScreenIntentApi = { true })
        assertTrue("not blocked before the channel exists", reader.read().criticalChannelAllowed)
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(context.getSystemService(android.os.PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)

        NotificationChannels.ensure(context)
        val allowed = reader.read()
        assertTrue(allowed.criticalChannelAllowed)
        assertEquals(
            "with the channel open, everything granted is tier 3",
            ResolvedTier.FULL,
            resolveDelivery(allowed).tier,
        )

        manager.deleteNotificationChannel(NotificationChannels.CRITICAL)
        manager.createNotificationChannel(
            NotificationChannel(NotificationChannels.CRITICAL, "blocked", NotificationManager.IMPORTANCE_NONE),
        )
        val blocked = reader.read()
        assertFalse("the Critical channel is blocked", blocked.criticalChannelAllowed)
        assertTrue("notifications are still on", blocked.notificationsEnabled)
        val resolution = resolveDelivery(blocked)
        assertFalse(resolution.fullScreenIntent)
        assertEquals("tier 3 fails for critical delivery", ResolvedTier.EXACT, resolution.tier)
        assertEquals(DeliveryCapability.TIER_2, resolution.capability)
    }

    // The delivery path is a function of the presentation, the domain's decision and the resolution.
    @Test
    fun `the path follows the presentation, the domain and the capability`() {
        fun rung(
            presentation: Presentation = Presentation.NORMAL,
            policy: RungDelivery = RungDelivery.RING,
            continuing: Boolean = false,
        ) = FiredRung(
            "a",
            1,
            EscalationRung(Instant.fromEpochMilliseconds(0), Channel.RING),
            presentation,
            policy,
            continuing,
        )

        fun resolution(
            exact: Boolean = true,
            fullScreen: Boolean = true,
            notifications: Boolean = true,
            battery: Boolean = true,
        ) = resolveDelivery(
            com.momtime.android.capability
                .CapabilityInputs(exact, fullScreen, notifications, battery, false, true),
        )

        assertEquals(DeliveryPath.RING, DeliveryPath.choose(rung(), resolution()))
        assertEquals(DeliveryPath.HEADS_UP, DeliveryPath.choose(rung(), resolution(fullScreen = false)))
        assertEquals(
            DeliveryPath.AUDIO_ONLY,
            DeliveryPath.choose(rung(), resolution(fullScreen = false, notifications = false)),
        )
        assertEquals(DeliveryPath.PLAIN, DeliveryPath.choose(rung(), resolution(exact = false)))
        assertEquals(
            DeliveryPath.SILENT_NOTICE,
            DeliveryPath.choose(rung(presentation = Presentation.SILENT_NOTICE), resolution()),
        )
        assertEquals(
            DeliveryPath.SILENT,
            DeliveryPath.choose(rung(policy = RungDelivery.SILENT_NOTIFICATION), resolution()),
        )
        // A silent notice is silent whatever the capability.
        assertEquals(
            DeliveryPath.SILENT_NOTICE,
            DeliveryPath.choose(rung(presentation = Presentation.SILENT_NOTICE), resolution(exact = false)),
        )
        // A rung that continues a ring in progress keeps ringing whatever it would have been alone.
        assertEquals(
            DeliveryPath.RING,
            DeliveryPath.choose(rung(presentation = Presentation.SILENT_NOTICE, continuing = true), resolution()),
        )
        assertEquals(
            DeliveryPath.RING,
            DeliveryPath.choose(rung(policy = RungDelivery.SILENT_NOTIFICATION, continuing = true), resolution()),
        )
        assertTrue(DeliveryPath.RING.rings && DeliveryPath.HEADS_UP.rings && DeliveryPath.AUDIO_ONLY.rings)
        assertFalse(DeliveryPath.PLAIN.rings || DeliveryPath.SILENT.rings || DeliveryPath.SILENT_NOTICE.rings)
    }

    // The ringer's audio is on the alarm stream (CLAUDE.md): USAGE_ALARM, so it carries through Do Not Disturb
    // without notification policy access.
    @Test
    fun `the ringer audio is described as an alarm`() {
        val attributes = AlarmAudio.attributes()
        assertEquals(AudioAttributes.USAGE_ALARM, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
    }
}
