package org.gnosisvpn.stub

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Connect and disconnect of a backend whose [up] and [down] block, run in order on [worker].
 * Like GnosisTunnel (both use [ConnectIntent]): a connect during a disconnect follows it with no
 * DISCONNECTED in between, and a disconnect after that connect still wins. [post] delivers
 * state changes to the listeners; [up] returns the CONNECTED detail.
 */
internal class TunnelSequence(
    private val worker: Executor,
    private val post: (Runnable) -> Unit,
    private val up: () -> String,
    private val down: () -> Unit,
    private val describe: (Exception) -> String,
) {
    val listeners = CopyOnWriteArrayList<Tunnel.Listener>()
    private val intent = ConnectIntent()

    @Volatile var state: Tunnel.State = Tunnel.State.DISCONNECTED
        private set

    fun connect() {
        synchronized(intent) {
            if (state == Tunnel.State.CONNECTING || state == Tunnel.State.CONNECTED) return
            intent.connect()   // wanted now, or once a pending disconnect has finished
            set(Tunnel.State.CONNECTING, null)
        }
        worker.execute {
            if (!intent.wanted) return@execute   // a later disconnect won
            try {
                val detail = up()
                set(Tunnel.State.CONNECTED, detail)
            } catch (e: Exception) {
                set(Tunnel.State.ERROR, describe(e))
            }
        }
    }

    fun disconnect() {
        synchronized(intent) {
            if (state == Tunnel.State.DISCONNECTED || state == Tunnel.State.DISCONNECTING) return
            intent.disconnect()
            set(Tunnel.State.DISCONNECTING, null)
        }
        worker.execute {
            val error = try { down(); null } catch (e: Exception) { describe(e) }
            // A connect since then keeps its CONNECTING; its task, queued behind this one, connects.
            synchronized(intent) {
                if (intent.disconnectDone()) set(if (error == null) Tunnel.State.DISCONNECTED else Tunnel.State.ERROR, error)
            }
        }
    }

    /** The backend went down by itself (revoked, another VPN took over) while connected. */
    fun wentDown(detail: String) = synchronized(intent) {
        if (state != Tunnel.State.CONNECTED) return
        intent.wanted = false
        set(Tunnel.State.DISCONNECTED, detail)
    }

    private fun set(s: Tunnel.State, detail: String?) {
        state = s
        post { listeners.forEach { it.onStateChanged(s, detail) } }
    }
}
