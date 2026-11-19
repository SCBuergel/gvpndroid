package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The return from Android's VPN settings is the engine's, not the activity's: it survives a recreated screen (R5-5). */
class R5_5SysVpnReturnTest {
    private var opens = true
    private val e = Engine().also { it.reset(); it.hooks = object : EngineHooks { override fun openSystemVpnSettings() = opens } }

    @Test fun returnIsSeenOncePerTripWhateverActivityResumes() {
        assertFalse(e.takeSysReturn())          // a resume from the share sheet or a browser
        e.lockdown = true
        e.openSysVpn("pay")
        assertEquals("pay", e.sysPurpose)
        assertTrue(e.takeSysReturn())           // a new UiActivity after rotation asks the engine
        assertFalse(e.takeSysReturn())
        e.sysVpnReturned(false)
        assertNull(e.sysPurpose)
        assertEquals("add", e.page())           // the pay flow continues
    }

    @Test fun noTripWhenTheSettingsDoNotOpen() {
        opens = false
        e.openSysVpn("pay")
        assertNull(e.sysPurpose)                // no stale purpose for a later resume
        assertFalse(e.takeSysReturn())
    }
}
