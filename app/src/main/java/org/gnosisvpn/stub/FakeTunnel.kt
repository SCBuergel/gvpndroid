package org.gnosisvpn.stub

import android.os.Handler
import android.os.Looper

/** Milestone 1 backend: pretends to connect after a short delay. Moves no traffic. */
class FakeTunnel : Tunnel {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<Tunnel.Listener>()
    private var connectedAt = 0L

    override var state: Tunnel.State = Tunnel.State.DISCONNECTED
        private set

    override fun connect() {
        if (state != Tunnel.State.DISCONNECTED && state != Tunnel.State.ERROR) return
        set(Tunnel.State.CONNECTING, "fake backend: no traffic is routed")
        main.postDelayed({
            connectedAt = System.currentTimeMillis()
            set(Tunnel.State.CONNECTED, null)
        }, 800)
    }

    override fun disconnect() {
        if (state != Tunnel.State.CONNECTED && state != Tunnel.State.CONNECTING) return
        main.removeCallbacksAndMessages(null)
        set(Tunnel.State.DISCONNECTING, null)
        main.postDelayed({ set(Tunnel.State.DISCONNECTED, null) }, 300)
    }

    override fun stats(): Tunnel.Stats? =
        if (state == Tunnel.State.CONNECTED) Tunnel.Stats(0, 0, connectedAt) else null

    override fun addListener(listener: Tunnel.Listener) { listeners += listener }
    override fun removeListener(listener: Tunnel.Listener) { listeners -= listener }

    private fun set(s: Tunnel.State, detail: String?) {
        state = s
        listeners.toList().forEach { it.onStateChanged(s, detail) }
    }
}
