package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/** The core's data left and free flag belong to the live session only; nothing is held at an exit after it (finding R4-1). */
class R4_1SessionDataTest {
    private fun loc(id: String) = T.Location(id, "Exit $id", "City $id", T.ExitHealth("DE", pingMs = 400, slotsTotal = 10, slotsFree = 5, pathValue = 0.9))

    private fun setup(): Pair<FakeTunnel, GnosisEngine> {
        val tn = FakeTunnel(); val e = GnosisEngine({ tn }); e.hooks = object : EngineHooks {}; e.start()
        tn.phase = "running"; tn.locations = listOf(loc("0xa"), loc("0xb")); e.tick()
        return tn to e
    }
    private fun GnosisEngine.tick() { t += 1; step(1.0) }
    private fun session(id: String, pay: T.Payment) = T.Session(id, 1, step = 9, steps = 10, bytesLeft = 400_000_000, bytesBought = 660_160_512, payment = pay)

    @Test fun disconnectDropsDataLeftAndFree() {
        val (tn, e) = setup()
        tn.state = T.State.CONNECTED; tn.session = session("0xa", T.Payment.FREE); e.tick()
        assertEquals(400.0, e.left("0xa"), 0.1); assertTrue(e.free)
        tn.state = T.State.DISCONNECTED; tn.session = null; e.tick()
        assertEquals(0.0, e.left("0xa"), 0.0); assertFalse(e.free)
        assertTrue(e.allocAt.isEmpty())
    }

    @Test fun errorDropsDataLeft() {
        val (tn, e) = setup()
        tn.state = T.State.CONNECTED; tn.session = session("0xa", T.Payment.FUNDED); e.tick()
        tn.state = T.State.ERROR; e.tick()
        assertEquals(0.0, e.left("0xa"), 0.0)
    }

    @Test fun switchDropsTheOldExitsData() {
        val (tn, e) = setup()
        tn.state = T.State.CONNECTED; tn.session = session("0xa", T.Payment.FREE); e.tick()
        tn.session = session("0xb", T.Payment.FUNDED).copy(bytesLeft = 600_000_000); e.tick()
        assertEquals("0xb", e.loc)
        assertEquals(0.0, e.left("0xa"), 0.0); assertEquals(600.0, e.left("0xb"), 0.1); assertFalse(e.free)
        assertEquals(setOf("0xb"), e.alloc.keys)
    }

    @Test fun onlyThePrototypeHoldsData() {
        assertFalse(GnosisEngine({ FakeTunnel() }).holdsData)
        assertTrue(DemoEngine().holdsData)
    }
}
