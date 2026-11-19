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
    /** Why the last connect attempt failed, in words, for the UI; null when there is nothing to say. */
    val lastError: String? get() = null
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    data class Stats(val rxBytes: Long, val txBytes: Long, val lastHandshakeEpochMs: Long?)

    /** An exit the user can choose. [id] is stable across app starts. */
    data class Location(val id: String, val name: String, val place: String, val health: ExitHealth? = null) {
        val label get() = if (place.isEmpty()) name else "$name — $place"
    }

    /**
     * What the backend measured about an exit; null fields are unknown.
     * [pathValue] is the best route's quality (0..1), [firstRelays] how many different first relays lead there.
     */
    data class ExitHealth(
        val countryCode: String? = null, val reachable: Boolean = true, val unrecoverable: Boolean = false,
        val pingMs: Int? = null, val checkedAtMs: Long? = null, val slotsTotal: Int? = null, val slotsFree: Int? = null,
        val load: Double? = null, val pathValue: Double? = null, val firstRelays: Int? = null, val checking: Boolean = false,
        val problem: String? = null,
        /** Why the exit can't be reached right now, in words (from its route health); null when it can. */
        val why: String? = null,
    )

    /** The live session as the backend reports it (data bought in chunks, paid from the wallet). */
    data class Session(
        val exitId: String, val sinceMs: Long, val step: Int? = null, val steps: Int = 0, val stepName: String? = null,
        val pingMs: Int? = null, val chunkBytes: Long? = null, val bytesLeft: Long? = null, val bytesSpent: Long? = null,
        val bytesBought: Long? = null, val cost: java.math.BigDecimal? = null, val emptyInSec: Long? = null,
        val payment: Payment? = null, val stalled: Boolean = false, val awaitingPayment: Boolean = false, val reconnecting: Boolean = false,
    )
    enum class Payment { FREE, PENDING, FAILED, FUNDED }

    /** Funds the backend pays with. Token amounts in whole tokens. */
    data class Wallet(
        val address: String, val traffic: java.math.BigDecimal, val gas: java.math.BigDecimal,
        val trafficLevel: Level, val gasLevel: Level, val trafficDeficit: java.math.BigDecimal? = null, val gasDeficit: java.math.BigDecimal? = null,
        /** [traffic] as data at the backend's price, if it has a fixed one. */
        val dataBytes: Long? = null,
    )
    enum class Level { GOOD, LOW, EMPTY }

    /** The backend's start-up: [step] of [steps] (for progress), a readable [name], or a failure. */
    data class Startup(val step: Int, val steps: Int, val name: String, val error: String? = null)

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

    /** Start-up progress while the backend is not running yet. */
    val startup: Startup? get() = null

    /** The backend stopped and cannot recover by itself (a readable reason). */
    val failure: String? get() = null

    /** The current session (connecting, connected or reconnecting), if any. */
    val session: Session? get() = null

    /** Size of one data chunk, if the backend sells data in chunks. */
    val chunkBytes: Long? get() = null

    /** The backend's wallet, refreshed in the background; null while unknown. */
    val wallet: Wallet? get() = null

    /** Asks the backend to measure the exits again soon (the user looks at the list). */
    fun refreshExits() {}

    /** How much the backend logs. */
    fun setLogLevel(level: LogLevel) {}

    /** Stops the backend's background work when the app switches to another backend. */
    fun shutdown() {}
}
