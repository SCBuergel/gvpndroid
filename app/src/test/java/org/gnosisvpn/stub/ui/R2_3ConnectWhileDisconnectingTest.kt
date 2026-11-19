package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/** A Connect tapped while the tunnel still disconnects reaches the tunnel (R2-3). */
class R2_3ConnectWhileDisconnectingTest {
    private class Hooks : EngineHooks {
        var connects = 0
        override fun connectVpn() { connects++ }
    }

    @Test fun connectDuringDisconnectingRequestsTheVpn() {
        val tn = FakeTunnel(); val h = Hooks()
        val e = GnosisEngine({ tn }); e.hooks = h; e.start()
        tn.phase = "running"; tn.locations = listOf(T.Location("0xe5cE", "GVPN-GER-1", "DE"))
        e.t += 1; e.step(1.0)
        tn.state = T.State.DISCONNECTING   // the core's Disconnect reply is still pending
        e.act("connect")
        assertEquals(1, h.connects)
    }
}
