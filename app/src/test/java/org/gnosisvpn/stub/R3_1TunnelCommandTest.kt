package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** runTunnelCommand: whether TunnelService has a tunnel to own after a start command (R3-1). */
class R3_1TunnelCommandTest {
    /** Like both backends: connect/disconnect change the state before returning, or do nothing. */
    private class FakeTunnel(override var state: Tunnel.State) : Tunnel {
        val calls = mutableListOf<String>()
        override fun connect() {
            calls += "connect"
            if (state != Tunnel.State.CONNECTING && state != Tunnel.State.CONNECTED) state = Tunnel.State.CONNECTING
        }
        override fun disconnect() {
            calls += "disconnect"
            if (state != Tunnel.State.DISCONNECTED && state != Tunnel.State.DISCONNECTING) state = Tunnel.State.DISCONNECTING
        }
        override fun stats(): Tunnel.Stats? = null
        override fun addListener(listener: Tunnel.Listener) {}
        override fun removeListener(listener: Tunnel.Listener) {}
    }

    private var wanted = false
    private fun run(action: String?, t: FakeTunnel) = runTunnelCommand(action, t, wanted) { wanted = it }

    @Test fun disconnectOnAnIdleTunnelStopsTheService() {
        wanted = true
        assertFalse(run(TunnelService.ACTION_DISCONNECT, FakeTunnel(Tunnel.State.DISCONNECTED)))
        assertFalse(wanted)
    }

    @Test fun disconnectWithSomethingToStopKeepsTheServiceUntilTheTunnelReports() {
        assertTrue(run(TunnelService.ACTION_DISCONNECT, FakeTunnel(Tunnel.State.CONNECTED)))
        assertTrue(run(TunnelService.ACTION_DISCONNECT, FakeTunnel(Tunnel.State.CONNECTING)))
    }

    @Test fun connectKeepsTheService() {
        assertTrue(run(TunnelService.ACTION_CONNECT, FakeTunnel(Tunnel.State.DISCONNECTED)))
        assertTrue(wanted)
    }

    @Test fun systemRestartReconnectsOnlyAfterAConnect() {
        run(TunnelService.ACTION_CONNECT, FakeTunnel(Tunnel.State.DISCONNECTED))
        val afterConnect = FakeTunnel(Tunnel.State.DISCONNECTED)   // the process died while connected
        assertTrue(run(null, afterConnect))
        assertEquals(listOf("connect"), afterConnect.calls)

        run(TunnelService.ACTION_DISCONNECT, FakeTunnel(Tunnel.State.CONNECTED))
        assertFalse(wanted)
        val afterDisconnect = FakeTunnel(Tunnel.State.DISCONNECTED)
        assertFalse(run(null, afterDisconnect))
        assertEquals(emptyList<String>(), afterDisconnect.calls)
    }

    @Test fun systemRestartOnAFreshInstallStaysOff() {
        val t = FakeTunnel(Tunnel.State.DISCONNECTED)
        assertFalse(run(null, t))
        assertEquals(emptyList<String>(), t.calls)
    }
}
