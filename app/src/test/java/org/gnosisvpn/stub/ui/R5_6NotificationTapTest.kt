package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notification actions that need the screen run once from UiActivity; hooks start nothing from a stopped screen (R5-6). */
class R5_6NotificationTapTest {
    @Test fun uiActionsAndConsentNeedingConnectsOpenTheScreen() {
        assertTrue(NoteTaps.needsUi("lockOn", needsConsent = false))
        assertTrue(NoteTaps.needsUi("addData", needsConsent = false))
        assertTrue(NoteTaps.needsUi("fundScreen", needsConsent = false))
        assertTrue(NoteTaps.needsUi("renew", needsConsent = true))
        assertFalse(NoteTaps.needsUi("renew", needsConsent = false))
        assertFalse(NoteTaps.needsUi("disconnect", needsConsent = true))
        assertFalse(NoteTaps.needsUi("cancel", needsConsent = true))
    }

    @Test fun aTapRunsOnceAndOnlyIfThisProcessPostedIt() {
        assertNull("never posted (another process)", NoteTaps.take("r56-a", "lockOn"))
        NoteTaps.arm("r56-a", "lockOn")
        assertEquals("r56-a" to "lockOn", NoteTaps.take("r56-a", "lockOn"))
        assertNull("replay on recreation", NoteTaps.take("r56-a", "lockOn"))
        NoteTaps.arm("r56-a", "lockOn")   // posted again
        assertEquals("r56-a" to "lockOn", NoteTaps.take("r56-a", "lockOn"))
        assertNull(NoteTaps.take(null, "lockOn"))
    }

    private class FakeScreen(override val started: Boolean) : UiFront {
        val calls = mutableListOf<String>()
        override fun openVpnSettings(): Boolean { calls += "settings"; return true }
        override fun openUrl(url: String) { calls += "url" }
        override fun share(text: String) { calls += "share" }
        override fun copy(label: String, text: String) { calls += "copy" }
        override fun startLogExport() { calls += "export" }
        override fun requestTile() { calls += "tile" }
        override fun connectVpn() { calls += "connect" }
    }

    @Test fun aStoppedScreenStartsNothingAndVpnSettingsDoNotCountAsOpened() {
        val stopped = FakeScreen(started = false)
        val hooks = AppHooks(android.content.ContextWrapper(null)) { stopped }
        assertFalse(hooks.openSystemVpnSettings())
        hooks.openUrl("https://example.org"); hooks.share("x"); hooks.exportLogs(); hooks.requestTile()
        assertEquals(emptyList<String>(), stopped.calls)

        val inFront = FakeScreen(started = true)
        val hooks2 = AppHooks(android.content.ContextWrapper(null)) { inFront }
        assertTrue(hooks2.openSystemVpnSettings())
        hooks2.openUrl("https://example.org")
        assertEquals(listOf("settings", "url"), inFront.calls)
    }
}
