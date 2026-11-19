package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WireGuard: a connect during a disconnect posts no DISCONNECTED; the service judges the state now (R3-4). */
class R3_4TunnelSequenceTest {
    private val tasks = ArrayDeque<Runnable>()
    private val calls = mutableListOf<String>()
    private val posted = mutableListOf<Tunnel.State>()
    private val seq = TunnelSequence({ tasks.addLast(it) }, { it.run() },
        up = { calls += "up"; "wg" }, down = { calls += "down" }, describe = { it.message ?: "" })
        .also { s -> s.listeners += Tunnel.Listener { st, _ -> posted += st } }

    private fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }

    @Test fun connectDuringDisconnectEndsConnectedWithoutDisconnected() {
        seq.connect(); runAll()
        posted.clear()
        seq.disconnect()
        seq.connect()                         // tapped while it still shows disconnecting
        runAll()
        assertEquals(listOf("up", "down", "up"), calls)
        assertEquals(listOf(Tunnel.State.DISCONNECTING, Tunnel.State.CONNECTING, Tunnel.State.CONNECTED), posted)
        assertEquals(Tunnel.State.CONNECTED, seq.state)
    }

    @Test fun aDisconnectAfterThatConnectStillWins() {
        seq.connect(); runAll()
        seq.disconnect(); seq.connect(); seq.disconnect()
        runAll()
        assertEquals(Tunnel.State.DISCONNECTED, seq.state)
        assertEquals(listOf("up", "down", "down"), calls)   // the queued up is skipped
    }

    @Test fun backendDownWhileConnectedIsDisconnected() {
        seq.connect(); runAll()
        seq.wentDown("revoked")
        assertEquals(Tunnel.State.DISCONNECTED, seq.state)
        seq.wentDown("again")                                // only while connected
        assertEquals(Tunnel.State.DISCONNECTED, posted.last())
        assertEquals(1, posted.count { it == Tunnel.State.DISCONNECTED })
    }

    @Test fun serviceStopsOnTheCurrentStateOnly() {
        assertTrue(serviceStops(Tunnel.State.DISCONNECTED))
        assertTrue(serviceStops(Tunnel.State.ERROR))
        assertFalse(serviceStops(Tunnel.State.CONNECTING))   // a DISCONNECTED posted before a connect
        assertFalse(serviceStops(Tunnel.State.CONNECTED))
        assertFalse(serviceStops(Tunnel.State.DISCONNECTING))
    }
}
