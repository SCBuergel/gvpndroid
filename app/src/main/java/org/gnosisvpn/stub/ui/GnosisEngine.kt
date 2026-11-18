package org.gnosisvpn.stub.ui

import org.gnosisvpn.stub.Tunnel

/**
 * The engine over the real Gnosis VPN core (through the app's [Tunnel] interface).
 *
 * What the core reports drives the screens: the funding gate (I-1, the node's address and what to
 * send), the start-up phases (I0), the exits (from the core's destinations), the connection
 * (connecting, connected, disconnected, errors) and Lockdown as Android reports it to the running
 * VpnService. The demo's data accounting (balance in MB, 600 MB per exit, renewals, pauses) has no
 * counterpart in client v1.0.0, so [metered] is false and the UI hides it; see README "New UI".
 */
class GnosisEngine(private val tunnel: () -> Tunnel) : Engine() {
    override val simulatesSystem = false
    override val metered = false

    private var fundAddress: String? = null
    private var need: String? = null
    private var lastState: Tunnel.State? = null
    private var lastIds = ""

    override fun reset() {
        super.reset()
        exits = mutableListOf(); loc = ""; lockdown = false; alwaysOn = false
        // no data accounting: never "needs 600 MB", never "balance empty"
        free = true; balance = 1e12; popover = false; warm = false
        syncT = 0.0
        lastIds = ""; lastState = null   // re-read everything from the tunnel on the next step
    }

    override fun start() { reset(); emit() }

    override fun step(dt: Double) {
        val tn = tunnel()
        val fr = tn.fundingRequest
        if (fr != null) { gate = true; fundAddress = fr.address; need = fr.need; syncT = null }
        else if (gate) { gate = false; syncT = 0.0 }
        if (!gate) {
            if (tn.phase == "running") { if (syncT != null) endSync() }
            else if (syncT == null) syncT = 0.0   // the clock advances it
        }
        // exits, from the core's destinations (keyed by exit address)
        val locs = tn.locations
        val ids = locs.joinToString { it.id }
        if (ids != lastIds) {
            lastIds = ids
            exits = locs.map { l ->
                Exit(l.id, city = l.label, country = l.name, place = l.place, ping = 0, rel = 2, spots = 1, measured = false,
                    countryCode = countryCode(l.place))
            }.toMutableList()
        }
        if (exits.none { it.id == loc }) {
            val saved = tn.selectedLocation?.id?.takeIf { id -> exits.any { it.id == id } }
            if (saved != null) { loc = saved; mode = "manual" } else loc = exits.firstOrNull()?.id ?: ""
        }
        // the connection
        val st = tn.state
        if (st != lastState) {
            when (st) {
                Tunnel.State.CONNECTING -> if (conn != Conn.CONNECTING) { conn = Conn.CONNECTING; cStart = now(); cDur = 15000 }
                Tunnel.State.CONNECTED -> {
                    conn = Conn.CONNECTED; connSec = 0.0; firstRun = false
                    recent = (listOf(Recent(loc, t)) + recent.filter { it.id != loc }).take(6).toMutableList()
                    org.gnosisvpn.stub.App.instance.readLockdown()?.let { lockdown = it; alwaysOn = alwaysOn || it }
                }
                Tunnel.State.DISCONNECTED -> if (conn != Conn.IDLE) { conn = Conn.IDLE; nextCheck = t + 20 }
                Tunnel.State.DISCONNECTING -> {}
                Tunnel.State.ERROR -> { conn = Conn.IDLE; setFlash(Tone.BAD, "Couldn’t connect.", "Try again, or pick another location.") }
            }
            lastState = st
        }
        if (conn == Conn.CONNECTED) connSec += dt
    }

    /** No better-exit checks: the core does not report exit metrics yet. */
    override fun runCheck() { nextCheck = Double.MAX_VALUE }

    /** Without metrics, "best" is the core's first available exit. */
    override fun best(except: String?): Exit? = exits.firstOrNull { usable(it) && it.id != except }

    override fun doConnect(id: String) {
        home(); sheet = null; fullMsg = null; flash = null; fq.clear(); offConfirm = false; better = null
        loc = id; conn = Conn.CONNECTING; pause = null; cStart = now(); cDur = 15000
        val tn = tunnel()
        tn.locations.firstOrNull { it.id == id }?.let { tn.selectLocation(it) }
        hooks.connectVpn()
        emit()
    }

    override fun disconnect() { super.disconnect(); hooks.disconnectVpn() }
    override fun onCancel() = hooks.disconnectVpn()
    override fun onTurnOff() = hooks.disconnectVpn()
    override fun onPayLockdownOff() = hooks.disconnectVpn()

    override fun address(): String = fundAddress ?: ""
    override fun fundUrl(): String = FAUCET_URL
    override fun fundingNeeds(): List<Triple<String, String, String>> {
        // "241.01 wxHOPR + 0.005 xDAI"
        val parts = need?.split("+")?.map { it.trim().split(" ") }.orEmpty()
        fun amount(token: String) = parts.firstOrNull { it.getOrNull(1) == token }?.getOrNull(0) ?: "—"
        return listOf(Triple("wxHOPR", "Pays for your traffic", amount("wxHOPR")), Triple("xDAI", "Pays network fees", amount("xDAI")))
    }
    override fun exitTag(e: Exit): String = e.id.removePrefix("0x").take(4).lowercase()
    override fun syncStep(): String? = when (tunnel().phase) {
        "preparing_safe", "deploying_safe" -> "Setting up your node’s Safe"
        null, "warmup" -> "Looking for other node runners"
        else -> "Determining available routes"
    }
    /** The core does not report progress: time-based, never past 95 % until it is running. */
    override fun syncLen() = Double.MAX_VALUE
    override fun syncProgress(): Float = ((syncT ?: 0.0) / 180).toFloat().coerceAtMost(0.95f)
    override fun endSync() {
        if (tunnel().phase != "running") return
        syncT = null
        if (startConnect && !firstRun) requestConnect(loc)
    }
    override fun connectStep(): String = "Opening a route to ${ex(loc).country}"

    companion object {
        /** "Edinburgh, UK" → GB, "FR" → FR; "" when the place has no two-letter country. */
        fun countryCode(place: String): String {
            val c = place.substringAfterLast(",").trim().uppercase()
            return when { c == "UK" -> "GB"; c.length == 2 && c.all { it in 'A'..'Z' } -> c; else -> "" }
        }

        /** The Gnosis VPN funding site (faucet codes). */
        const val FAUCET_URL = "https://faucet.vpn.gnosis.eth.limo/"
    }
}
