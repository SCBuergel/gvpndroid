package org.gnosisvpn.stub

/**
 * The app's only view of the VPN. The UI talks to this interface and nothing else,
 * so the backend can be swapped: a fake one now, plain WireGuard next, and later
 * the Gnosis VPN client (WireGuard carried over a HOPR session).
 */
interface Tunnel {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    data class Stats(val rxBytes: Long, val txBytes: Long, val lastHandshakeEpochMs: Long?)

    fun interface Listener {
        fun onStateChanged(state: State, detail: String?)
    }

    val state: State

    fun connect()
    fun disconnect()
    fun stats(): Stats?

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
}
