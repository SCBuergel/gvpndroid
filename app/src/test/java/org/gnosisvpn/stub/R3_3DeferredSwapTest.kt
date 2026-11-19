package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gnosisvpn.stub.Tunnel.State as S

/** DeferredSwap: the developer screen's backend switch never swaps under a live tunnel (R3-3). */
class R3_3DeferredSwapTest {
    private var swaps = 0
    private val swap = DeferredSwap { swaps++ }

    @Test fun aTunnelDownAtTheEndOfTheCopySwapsAtOnce() {
        assertTrue(swap.request(S.DISCONNECTED)); assertEquals(1, swaps)
        assertTrue(swap.request(S.ERROR)); assertEquals(2, swaps)
        assertFalse(swap.onState(S.DISCONNECTED)); assertEquals(2, swaps)
    }

    @Test fun aTunnelConnectedDuringTheCopySwapsOnlyOnceItIsDown() {
        assertFalse(swap.request(S.CONNECTED)); assertEquals(0, swaps); assertTrue(swap.pending)
        for (s in listOf(S.CONNECTING, S.CONNECTED, S.DISCONNECTING)) assertFalse(swap.onState(s))
        assertEquals(0, swaps)
        assertTrue(swap.onState(S.DISCONNECTED)); assertEquals(1, swaps); assertFalse(swap.pending)
        assertFalse(swap.onState(S.ERROR)); assertEquals(1, swaps)   // once
    }
}
