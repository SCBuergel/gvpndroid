package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/** A core restart under a live connection keeps the home screen with a working stop (finding R4-6). */
class R4_6CoreRestartTest {
    private fun loc(id: String) = T.Location(id, "Exit $id", "City $id", T.ExitHealth("DE", pingMs = 400, slotsTotal = 10, slotsFree = 5, pathValue = 0.9))
    private val session = T.Session("0xa", 1, step = 9, steps = 10, bytesLeft = 400_000_000, bytesBought = 660_160_512, payment = T.Payment.FUNDED)
    private class Hooks : EngineHooks { var stops = 0; var connects = 0; override fun disconnectVpn() { stops++ }; override fun connectVpn() { connects++ } }
    private fun GnosisEngine.tick(n: Int = 1) = repeat(n) { t += 1; step(1.0) }

    @Test fun aRestartWhileConnectedIsAReconnectNotAStartUp() {
        val tn = FakeTunnel(); val h = Hooks(); val e = GnosisEngine({ tn }); e.hooks = h; e.start()
        tn.phase = "running"; tn.locations = listOf(loc("0xa")); e.tick()
        tn.state = T.State.CONNECTED; tn.session = session; e.tick()
        assertNull(e.syncT)
        e.go("settings")
        // GnosisTunnel.onCoreExit(RESTART): status cleared, tun kept, a new core starting
        tn.phase = null; tn.state = T.State.CONNECTING; tn.session = null; e.tick(5)
        assertNull("start-up sync screen shown", e.syncT)
        assertEquals("the open page was closed", "settings", e.page())
        assertNotEquals(Conn.IDLE, e.conn)
        e.act("cancel")
        assertEquals("the stop did not reach the tunnel", 1, h.stops)
        tn.state = T.State.DISCONNECTED; e.tick(3)   // the stop arrived; no core started yet
        assertNull("stopped reconnect lands on the sync screen", e.syncT)
        assertEquals(Conn.IDLE, e.conn)
        tn.phase = "running"; tn.locations = listOf(loc("0xa")); e.tick(3)   // the restarted core runs
        assertEquals("the stop was undone by the start-up auto-connect", 0, h.connects)
        assertEquals(Conn.IDLE, e.conn)
    }

    @Test fun aUiStartedDuringARestartUnderAConnectionShowsHomeNotSync() {
        val tn = FakeTunnel(); val h = Hooks(); val e = GnosisEngine({ tn }); e.hooks = h
        tn.state = T.State.CONNECTING; e.start(); e.tick()   // reset() starts in sync; the tun is held
        assertNull(e.syncT)
        assertNotEquals(Conn.IDLE, e.conn)
    }

    @Test fun aCoreStartWithoutAConnectionStillShowsTheSyncScreen() {
        val tn = FakeTunnel(); val e = GnosisEngine({ tn }); e.hooks = Hooks(); e.start()
        tn.phase = "running"; tn.locations = listOf(loc("0xa")); e.tick()
        assertNull(e.syncT)
        tn.phase = null; e.tick()   // idle core restart (e.g. Retry)
        assertNotNull(e.syncT)
    }
}
