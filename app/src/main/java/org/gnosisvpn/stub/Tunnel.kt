package org.gnosisvpn.stub

/**
 * The app's only view of the VPN. The UI talks to this interface and nothing else, so backends
 * can be swapped: plain WireGuard ([WireGuardTunnel]) and the Gnosis VPN client core
 * ([GnosisTunnel], WireGuard carried over a HOPR session).
 *
 * The members below the core five are optional capabilities with "not supported" defaults, so a
 * UI can show them when a backend offers them without knowing which backend it talks to.
 */
interface Tunnel {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    data class Stats(val rxBytes: Long, val txBytes: Long, val lastHandshakeEpochMs: Long?)

    /** An exit the user can choose. [id] is stable across app starts. */
    data class Location(val id: String, val name: String, val place: String) {
        val label get() = if (place.isEmpty()) name else "$name — $place"
    }

    /** The backend cannot run until [address] is funded with [need] (on Gnosis Chain). */
    data class FundingRequest(val address: String, val need: String)

    fun interface Listener {
        fun onStateChanged(state: State, detail: String?)
    }

    val state: State

    fun connect()
    fun disconnect()
    fun stats(): Stats?

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    /** Exits this backend can connect to; empty when there is no choice (or none known yet). */
    val locations: List<Location> get() = emptyList()

    /** The chosen location, if it is among [locations]. */
    val selectedLocation: Location? get() = null

    /** Chooses where to connect; an active connection moves there without traffic leaving unprotected. */
    fun selectLocation(location: Location) {}

    /** Set while the backend waits for funds before it can connect. */
    val fundingRequest: FundingRequest? get() = null

    /** The backend's start-up phase ("warmup", "preparing_safe", "deploying_safe", "running"), if it has one. */
    val phase: String? get() = null

    /** How much the backend logs. */
    fun setLogLevel(level: LogLevel) {}

    /** Stops the backend's background work when the app switches to another backend. */
    fun shutdown() {}
}
