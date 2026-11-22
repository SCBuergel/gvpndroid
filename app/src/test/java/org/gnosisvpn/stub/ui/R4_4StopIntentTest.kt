package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/** A user's stop holds until the tunnel is down; the tunnel's last state does not bring the connection back (finding R4-4). */
class R4_4StopIntentTest {
    private fun loc(id: String) = T.Location(id, "Exit $id", "City $id", T.ExitHealth("DE", pingMs = 400, slotsTotal = 10, slotsFree = 5, pathValue = 0.9))
    private val session = T.Session("0xa", 1, step = 9, steps = 10, bytesLeft = 400_000_000, bytesBought = 660_160_512, payment = T.Payment.FUNDED)
    private class Hooks : EngineHooks { var connects = 0; var stops = 0; override fun connectVpn() { connects++ }; override fun disconnectVpn() { stops++ } }

    private fun setup(st: T.State): Triple<FakeTunnel, GnosisEngine, Hooks> {
        val tn = FakeTunnel(); val h = Hooks(); val e = GnosisEngine({ tn }); e.hooks = h; e.start()
        tn.phase = "running"; tn.locations = listOf(loc("0xa")); e.tick()
        tn.state = st; tn.session = session; e.tick()
        return Triple(tn, e, h)
    }
    private fun GnosisEngine.tick(n: Int = 1) = repeat(n) { t += 1; step(1.0) }

    @Test fun disconnectHoldsWhileTheTunnelIsStillUpAndDisconnecting() {
        val (tn, e, h) = setup(T.State.CONNECTED)
        assertEquals(Conn.CONNECTED, e.conn)
        e.act("disconnect"); assertEquals(1, h.stops)
        val recent = e.recent.toList()
        e.tick(3)   // the service has not called tunnel.disconnect() yet
        assertEquals(Conn.IDLE, e.conn); assertEquals(recent, e.recent)
        tn.state = T.State.DISCONNECTING; e.tick(3)
        assertEquals(Conn.IDLE, e.conn)
        tn.state = T.State.DISCONNECTED; tn.session = null; e.tick()
        assertEquals(Conn.IDLE, e.conn)
    }

    @Test fun cancelHoldsWhileConnecting() {
        val (tn, e, _) = setup(T.State.CONNECTING)
        tn.session = null; e.tick()
        assertEquals(Conn.CONNECTING, e.conn)
        e.act("cancel"); e.tick(3)
        assertEquals(Conn.IDLE, e.conn)
    }

    @Test fun connectAfterTheStopWorks() {
        val (tn, e, h) = setup(T.State.CONNECTED)
        e.act("disconnect"); tn.state = T.State.DISCONNECTING; e.tick()
        e.doConnect("0xa")   // connect again while the tunnel still goes down (R2-3)
        assertEquals(1, h.connects)
        e.tick(); assertEquals(Conn.CONNECTING, e.conn)
        tn.state = T.State.CONNECTED; e.tick()
        assertEquals(Conn.CONNECTED, e.conn)
    }

    @Test fun aStopWhileDownLeavesLaterConnectsAlone() {
        val tn = FakeTunnel(); val e = GnosisEngine({ tn }); e.hooks = Hooks(); e.start()
        tn.phase = "running"; tn.locations = listOf(loc("0xa")); e.tick()
        e.act("disconnect")   // nothing to stop
        tn.state = T.State.CONNECTED; tn.session = session; e.tick()   // e.g. always-on starts it
        assertTrue(e.conn == Conn.CONNECTED)
    }

    @Test fun aConnectFromOutsideTheEngineAfterTheStopIsShown() {
        val (tn, e, _) = setup(T.State.CONNECTED)
        e.act("disconnect"); tn.state = T.State.DISCONNECTING; e.tick()
        tn.state = T.State.CONNECTING; tn.session = null; e.tick()   // e.g. Always-on, without DISCONNECTED in between
        assertEquals(Conn.CONNECTING, e.conn)
        tn.state = T.State.CONNECTED; tn.session = session; e.tick()
        assertEquals(Conn.CONNECTED, e.conn)
    }

    @Test fun aStopThatNeverArrivesIsGivenUp() {
        val (_, e, _) = setup(T.State.CONNECTED)
        e.act("disconnect"); e.tick(GnosisEngine.STOP_WAIT.toInt())
        assertEquals(Conn.IDLE, e.conn)
        e.tick(2)   // the service never took it: the tunnel is still up, so show it
        assertEquals(Conn.CONNECTED, e.conn)
    }
}
