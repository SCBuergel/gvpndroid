package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/** A switch keeps the user's pick until the core reports a session there or stops (finding R4-2). */
class R4_2SwitchPickTest {
    private fun loc(id: String, name: String) = T.Location(id, name, "City $name", T.ExitHealth("DE", pingMs = 400, slotsTotal = 10, slotsFree = 5, pathValue = 0.9))
    private fun session(id: String) = T.Session(id, 1, step = 9, steps = 10, bytesLeft = 400_000_000, bytesBought = 660_160_512, payment = T.Payment.FUNDED)

    private fun connectedToA(): Pair<FakeTunnel, GnosisEngine> {
        val tn = FakeTunnel(); val e = GnosisEngine({ tn }); e.hooks = object : EngineHooks {}; e.start()
        tn.phase = "running"; tn.locations = listOf(loc("0xa", "Alpha"), loc("0xb", "Bravo")); e.tick()
        tn.state = T.State.CONNECTED; tn.session = session("0xa"); e.tick()
        assertEquals("0xa", e.loc); assertEquals(Conn.CONNECTED, e.conn)
        return tn to e
    }
    private fun GnosisEngine.tick() { t += 1; step(1.0) }

    @Test fun staleSessionDoesNotTakeThePickBack() {
        val (tn, e) = connectedToA()
        e.doConnect("0xb")
        assertEquals(listOf("0xb"), tn.selected)
        e.tick()   // the core still reports A's session, the tunnel still CONNECTED
        assertEquals("0xb", e.loc); assertEquals(Conn.CONNECTING, e.conn)
        tn.state = T.State.CONNECTING; e.tick()
        assertEquals("0xb", e.loc); assertEquals(Conn.CONNECTING, e.conn)
        tn.session = null; e.tick()
        assertEquals("0xb", e.loc)
        tn.state = T.State.ERROR; tn.lastErrorText = "unable to connect"; e.tick()
        assertEquals("0xb", e.loc); assertEquals(Conn.IDLE, e.conn)
        assertEquals("Couldn’t connect to Bravo.", e.flash?.lead)
    }

    @Test fun sessionAtThePickConnects() {
        val (tn, e) = connectedToA()
        e.doConnect("0xb"); tn.state = T.State.CONNECTING; e.tick()
        tn.state = T.State.CONNECTED; tn.session = session("0xb"); e.tick()
        assertEquals("0xb", e.loc); assertEquals(Conn.CONNECTED, e.conn)
        // with the pick reached, loc follows the core again (it may move the session itself)
        tn.session = session("0xa"); e.tick()
        assertEquals("0xa", e.loc); assertTrue(e.conn == Conn.CONNECTED)
    }

    @Test fun anExitTheCoreWasNotToldAboutIsNotPending() {
        val (tn, e) = connectedToA()
        e.doConnect("0xgone")   // no longer in the exit list: selectLocation is not called
        assertEquals(emptyList<String>(), tn.selected)
        e.tick()   // the core stays at A: the engine follows it instead of waiting for a pick it never got
        assertEquals("0xa", e.loc); assertEquals(Conn.CONNECTED, e.conn)
    }
}
