package com.momtime.android.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the manifest declares for the system broadcasts (ADR 0067): one receiver, not exported, listening to exactly
 * the four actions that are acted on, and not to `LOCKED_BOOT_COMPLETED` (nothing here is direct boot aware) or the
 * timezone broadcast (what it should do is an open question). The expected list is written out here, by hand, and
 * does not read [SystemEvent]; a second test then says the two agree. This reads the source manifest: it shows what
 * is declared, not that the platform delivers to it (`MANUAL_CHECKS.md` P2-26 to P2-29).
 */
class SystemManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private val expectedActions =
        setOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.TIME_SET",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )

    private fun receiver(): Element {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(File("src/main/AndroidManifest.xml"))
                .documentElement
        val receivers = root.getElementsByTagName("receiver")
        return (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == ".system.SystemBroadcastReceiver" }
    }

    private fun actions(receiver: Element): Set<String> {
        val nodes = receiver.getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(android, "name") }.toSet()
    }

    @Test
    fun `the receiver is not exported and is not direct boot aware`() {
        val receiver = receiver()

        assertEquals("false", receiver.getAttributeNS(android, "exported"))
        assertFalse(
            "nothing runs before the first unlock",
            receiver.getAttributeNS(android, "directBootAware") == "true",
        )
    }

    @Test
    fun `the receiver listens to exactly the expected actions`() {
        val declared = actions(receiver())

        assertEquals(expectedActions, declared)
        assertFalse("LOCKED_BOOT_COMPLETED is not handled", "android.intent.action.LOCKED_BOOT_COMPLETED" in declared)
        assertFalse("the timezone broadcast is not declared", "android.intent.action.TIMEZONE_CHANGED" in declared)
    }

    @Test
    fun `every event the code acts on is declared`() {
        val declared = actions(receiver())

        for (event in SystemEvent.entries) {
            assertTrue("${event.name} (${event.action}) is not declared", event.action in declared)
        }
        assertEquals(expectedActions, SystemEvent.entries.map { it.action }.toSet())
    }
}
