package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/** A Connect from Settings > Preferred exit shows its auto-renew intro at once, not behind Settings (finding R5-8). */
class R5_8ConnectFromSettingsTest {
    private var connects = 0
    private val tn = FakeTunnel()
    private val e = GnosisEngine({ tn }).also { e ->
        e.hooks = object : EngineHooks { override fun connectVpn() { connects++ } }
        e.start()
        tn.phase = "running"
        tn.locations = listOf("0xa", "0xb").map { T.Location(it, "Exit $it", "City", T.ExitHealth("DE", pingMs = 400, slotsTotal = 10, slotsFree = 5, pathValue = 0.9, firstRelays = 2)) }
        e.t += 1; e.step(1.0)
    }

    @Test fun introShowsOverTheMainScreen() {
        e.popover = true                        // "Hide data use before connecting" off; intro not seen yet
        e.go("settings"); e.go("pick")          // Settings > Preferred exit
        e.act("pick2", mapOf("id" to "0xb"))
        assertNull(e.page())                    // back on the main screen
        val sh = e.visibleSheet()
        assertNotNull(sh); assertEquals("quota", sh!!.type); assertTrue(sh.intro)
        assertEquals(0, connects)               // nothing connects before the intro is answered
        assertEquals(Conn.IDLE, e.conn)
    }

    @Test fun anEngineRaisedQuotaSheetStillWaitsWhileAPageIsOpen() {
        e.go("settings")
        e.sheet = Sheet("quota", "0xa")
        assertNull(e.visibleSheet())
    }
}
