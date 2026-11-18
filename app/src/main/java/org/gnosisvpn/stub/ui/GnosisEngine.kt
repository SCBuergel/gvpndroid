package org.gnosisvpn.stub.ui

import android.content.SharedPreferences
import org.gnosisvpn.stub.Tunnel
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** How long after a failed PIX payment the app keeps saying so, while the core retries. */
private const val PAY_FAIL_HOLD_S = 180.0

/**
 * The app's engine: every screen shows what the Gnosis VPN core reports through [Tunnel].
 *
 * - Start-up: the funding gate (run mode preparing_safe: the funding site and the node's address), then "Discovering the network" with the core's real start-up steps, or the failure page.
 * - Exits: the core's destinations with their measured route (path value, first relays), latency,
 *   slots and load (route health and quick checks).
 * - The session: connect phases, connected, reconnecting and stalls, and the data bought for it in
 *   chunks (PIX): data left, renewals, payments pending or failed. The core renews by itself.
 * - The wallet: the Safe's wxHOPR (shown as data at the configured price) and the node's xDAI, with
 *   the core's own low/empty levels; a balance that goes up is a payment received.
 * - Lockdown, as Android reports it to the running VpnService.
 *
 * No value here is simulated: what the core does not report is not shown.
 */
class GnosisEngine(private val tunnel: () -> Tunnel, private val prefs: SharedPreferences? = null) : Engine() {
    override val simulatesSystem = false
    override val autoRenewFixed = true
    override val canUploadLogs = false
    override val retryCountdown = false

    private var fundAddress: String? = null
    private var need: String? = null
    private var lastState: Tunnel.State? = null
    private var lastIds = ""
    private var wallet: Tunnel.Wallet? = null
    private var session: Tunnel.Session? = null
    private var lastBought: Long? = null
    private var lastPayFail = false
    /**
     * When (engine time) a PIX payment for this connection last failed. After a failed payment the
     * core ends the exit's hold and reconnects, so the session alone flips between reconnecting
     * and connected. This keeps the app on "Couldn't pay for data" until a payment goes through.
     */
    private var payFailAt: Double? = null
    private var rx: Pair<Double, Double>? = null
    private var lastCounters: Tunnel.Stats? = null
    private var lastCountersAt = 0L
    private var sessionStart: Tunnel.Stats? = null
    /** The wallet can be shown as data (the backend has a fixed price). */
    private var dataKnown = false

    override val chunk: Double get() = (tunnel().chunkBytes ?: session?.chunkBytes)?.let { it / 1e6 } ?: COST

    override fun reset() {
        super.reset()
        exits = mutableListOf(); loc = ""; lockdown = false; alwaysOn = false
        balance = 0.0; added = 0.0; popover = false; warm = false; autoRenew = true; arPast4 = true; free = false
        syncT = 0.0
        lastIds = ""; lastState = null; wallet = null; session = null; lastBought = null; dataKnown = false
        load()
    }

    override fun start() { reset(); emit() }

    /* ---- user preferences that survive restarts ---- */
    private fun load() {
        val p = prefs ?: return
        startConnect = p.getBoolean("startConnect", false); firstRun = p.getBoolean("firstRun", true)
        popover = p.getBoolean("popover", false); icons = p.getString("icons", "node") ?: "node"
        simple = p.getBoolean("simple", true); details = !simple; arIntro = p.getBoolean("arIntro", false)
        qsTile = p.getBoolean("qsTile", false); mode = p.getString("pickMode", "auto") ?: "auto"
    }
    private fun save() {
        prefs?.edit()?.putBoolean("startConnect", startConnect)?.putBoolean("firstRun", firstRun)?.putBoolean("popover", popover)
            ?.putString("icons", icons)?.putBoolean("simple", simple)?.putBoolean("arIntro", arIntro)?.putBoolean("qsTile", qsTile)
            ?.putString("pickMode", mode)?.apply()
    }

    override fun step(dt: Double) {
        val tn = tunnel()
        // failure: the node stopped (the core reports it), or the core itself stopped
        val failure = tn.failure ?: if (tn.state == Tunnel.State.ERROR && tn.phase == null) "Gnosis VPN could not start" else null
        if (failure != null) { if (fail == null) { fail = Failure(failure, failureCode(tn)); home(); sheet = null } }
        else if (fail != null && tn.phase != null) fail = null

        // funding gate: the node waits for its first funds
        val fr = tn.fundingRequest
        if (fr != null) {
            if (!gate) { gate = true; home(); sheet = null }
            fundAddress = fr.address; need = fr.need; syncT = null
        } else if (gate) {
            gate = false; pay = null; syncT = 0.0
            fire("n8:gate", Note("n8", "Payment received", "Your node is funded. Gnosis VPN is getting ready.", listOf("Close" to "nclose"), code = "N8"))
        }
        if (!gate && fail == null) {
            if (tn.phase == "running") { if (syncT != null) endSync() }
            else if (syncT == null) { syncT = 0.0; home(); sheet = null }
        }

        syncExits(tn)
        syncWallet(tn)
        syncSession(tn, dt)
        syncRates(tn)
    }

    private fun failureCode(tn: Tunnel) = "GVPN-" + (tn.phase ?: "core").uppercase(Locale.ROOT).replace('_', '-')

    /* ---- exits ---- */
    private fun syncExits(tn: Tunnel) {
        val locs = tn.locations
        val ids = locs.joinToString { it.id + it.health.hashCode() }
        if (ids != lastIds) {
            lastIds = ids
            exits = locs.map { l -> toExit(l) }.toMutableList()
        }
        if (exits.none { it.id == loc }) {
            val saved = tn.selectedLocation?.id?.takeIf { id -> exits.any { it.id == id } }
            loc = if (mode == "manual" && saved != null) saved else best()?.id ?: saved ?: exits.firstOrNull()?.id ?: ""
        }
        // Auto picks the best exit while disconnected, as soon as measurements change it
        if (mode == "auto" && conn == Conn.IDLE && page() == null) {
            val b = best()
            if (b != null && b.id != loc && grade(b).k == "ok" && (grade(ex(loc)).sc ?: -2.0) < (grade(b).sc ?: -2.0) - 0.05) {
                loc = b.id; swapAnim = true
                setFlash(Tone.INFO, "${b.city} is now the best exit.", "Auto keeps this up to date until you connect.")
                later(700) { swapAnim = false; emit() }
            }
        }
    }

    private fun toExit(l: Tunnel.Location): Exit {
        val h = l.health ?: Tunnel.ExitHealth()
        val cc = h.countryCode ?: countryCode(l.place).ifEmpty { null }
        val country = cc?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH).ifEmpty { null } } ?: l.name
        val total = h.slotsTotal ?: 0; val free = h.slotsFree ?: 0
        val measured = h.pingMs != null
        val busy = h.load?.let { if (it < 0.5) "Low" else if (it < 0.85) "Medium" else "High" } ?: ""
        return Exit(
            l.id, city = l.name, country = country, place = l.place,
            ping = h.pingMs ?: 0, rel = (h.firstRelays ?: 0).coerceIn(0, 3), spots = free, slots = total,
            pv = h.pathValue ?: 0.0, fr = h.firstRelays ?: 0, measured = measured, busy = busy,
            full = measured && total > 0 && free <= 0, offline = h.unrecoverable, routable = h.reachable,
            countryCode = cc ?: "",
        ).also { it.checkedAt = h.checkedAtMs; it.load = h.load; it.checking = h.checking }
    }

    /** While no exit has a good route yet (the core keeps probing), "best" is the one with the best path. */
    override fun best(except: String?): Exit? =
        super.best(except) ?: exits.filter { usable(it) && it.routable && it.id != except }.maxByOrNull { it.pv }
            ?: exits.firstOrNull { usable(it) && it.id != except }

    /* ---- wallet ---- */
    private fun syncWallet(tn: Tunnel) {
        val w = tn.wallet ?: return
        val before = wallet; wallet = w
        val est = w.dataBytes?.let { it / 1e6 }
        dataKnown = est != null
        val b = when (w.trafficLevel) {
            Tunnel.Level.EMPTY -> 0.0
            Tunnel.Level.LOW -> minOf(est ?: chunk, chunk)
            Tunnel.Level.GOOD -> max(est ?: (chunk * 4), chunk * 1.01)
        }
        if (before != null && w.traffic > before.traffic + BigDecimal("0.5")) {
            val add = w.dataBytes?.let { d -> before.dataBytes?.let { (d - it) / 1e6 } }
            val what = if (add != null && add > 0) "${mb(add)} added" else "${tokens(w.traffic - before.traffic)} wxHOPR added"
            pay = null; balEp++
            fire("n8:${t.toInt()}", Note("n8", "Payment received", "$what. Your balance is ${mbOrTokens(b, w)}.${if (ldOffPay) " Lockdown is still off." else ""}",
                if (ldOffPay) listOf("Turn Lockdown back on" to "lockOn", "Close" to "nclose") else listOf("Close" to "nclose"), code = if (ldOffPay) "N7" else "N8"))
            setFlash(Tone.OK, "Payment received.", "$what; your balance is ${mbOrTokens(b, w)}.")
            if (conn == Conn.PAUSED && pause == Pause.BALANCE) pause = Pause.RENEWFAIL
        }
        if (before?.trafficLevel != w.trafficLevel) balEp++
        balance = b
        if (w.trafficLevel == Tunnel.Level.LOW) fire("u4:$balEp", Note("u4", "Balance running low", "${balanceText()} left. ${usePhrase()}", listOf("Add data" to "addData", "Close" to "nclose")))
        if (w.trafficLevel == Tunnel.Level.EMPTY) fire("u5:$balEp", Note("u5", "Balance empty", "No funds left to buy more data. Add funds to keep browsing.", listOf("Add data" to "addData", "Close" to "nclose")))
        if (w.gasLevel == Tunnel.Level.EMPTY) fire("gas:$balEp", Note("u5", "Network fees empty", "Your node has no xDAI left for network fees. Send a little xDAI to your VPN address.", listOf("Add data" to "addData", "Close" to "nclose")))
    }

    private fun mbOrTokens(b: Double, w: Tunnel.Wallet) = if (w.dataBytes != null) mb(b) else "${tokens(w.traffic)} wxHOPR"

    override val balanceKnown: Boolean get() = wallet != null

    override fun balanceText(): String {
        val w = wallet ?: return "—"
        return if (dataKnown) mb(balance) else "${tokens(w.traffic)} wxHOPR"
    }

    /* ---- the session ---- */
    private fun syncSession(tn: Tunnel, dt: Double) {
        val st = tn.state
        val s = tn.session?.takeIf { st != Tunnel.State.DISCONNECTED && st != Tunnel.State.DISCONNECTING }
        session = s
        if (st != lastState) {
            if (st == Tunnel.State.ERROR && lastState != null) { conn = Conn.IDLE; setFlash(Tone.BAD, "Couldn’t connect.", "Try again, or pick another location.") }
            if (st == Tunnel.State.CONNECTED) runCatching { org.gnosisvpn.stub.App.instance.readLockdown() }.getOrNull()?.let { lockdown = it; alwaysOn = alwaysOn || it }
            lastState = st
        }
        when {
            st == Tunnel.State.DISCONNECTED || st == Tunnel.State.ERROR -> {
                if (conn != Conn.IDLE) { conn = Conn.IDLE; pause = null; offConfirm = false; nextCheck = t + 20; sessionStart = null }
                payFailAt = null
            }
            st == Tunnel.State.DISCONNECTING -> {}
            s == null -> if (conn != Conn.CONNECTING) { conn = Conn.CONNECTING; cStart = now(); cDur = 60_000 }   // waiting for the core
            else -> {
                if (s.exitId.isNotEmpty() && exits.any { it.id == s.exitId }) loc = s.exitId
                val e = ex(loc)
                s.bytesLeft?.let { alloc[loc] = it / 1e6 }
                free = s.payment == Tunnel.Payment.FREE
                s.pingMs?.let { if (it > 0) { e.ping = it } }
                // renewals: the core bought another chunk
                val bought = s.bytesBought
                if (bought != null) {
                    val prev = lastBought
                    if (prev != null && bought > prev && conn == Conn.CONNECTED) {
                        allocN++; allocAt[loc] = t
                        setFlash(Tone.OK, "Renewed automatically.", "${mb((bought - prev) / 1e6)} more at ${e.city}.")
                        fire("u2:$allocN", Note("u2", "Renewing automatically", "${mb((bought - prev) / 1e6)} more at ${e.city}. Shown in the app only.", silent = true))
                    }
                    lastBought = bought
                }
                val reachable = e.routable && !e.offline
                if (s.payment == Tunnel.Payment.FAILED) payFailAt = t
                else if (s.payment == Tunnel.Payment.FUNDED && !s.awaitingPayment) payFailAt = null
                val payFailing = payFailAt?.let { t - it < PAY_FAIL_HOLD_S } == true
                val newConn = when {
                    s.reconnecting && !reachable -> Conn.PAUSED
                    // Paused only while the exit actually holds traffic (or the core reconnects after
                    // ending the hold); with data still flowing it stays connected, with a flash.
                    payFailing && (s.awaitingPayment || s.reconnecting) -> Conn.PAUSED
                    s.reconnecting -> Conn.RECONNECTING
                    s.step != null && s.step < s.steps - 1 && !s.stalled && tn.state != Tunnel.State.CONNECTED -> Conn.CONNECTING
                    s.awaitingPayment && (wallet?.trafficLevel == Tunnel.Level.EMPTY) -> Conn.PAUSED
                    s.stalled -> Conn.RECONNECTING
                    else -> Conn.CONNECTED
                }
                val newPause = when {
                    newConn != Conn.PAUSED -> null
                    s.reconnecting && !reachable -> Pause.OFFLINE
                    wallet?.trafficLevel == Tunnel.Level.EMPTY -> Pause.BALANCE
                    else -> Pause.RENEWFAIL
                }
                if (newConn == Conn.CONNECTING && conn != Conn.CONNECTING) { cStart = now(); cDur = 60_000 }
                if (newConn == Conn.CONNECTED && conn != Conn.CONNECTED) {
                    val fromPause = conn == Conn.PAUSED || conn == Conn.RECONNECTING
                    connSec = 0.0; if (firstRun) { firstRun = false; save() }
                    recent = (listOf(Recent(loc, t)) + recent.filter { it.id != loc }).take(6).toMutableList()
                    if (fromPause) setFlash(Tone.OK, "Back online.", "Connected through ${e.city} again.")
                    sessionStart = sessionStart ?: tn.stats()
                }
                if (newPause == Pause.OFFLINE && pause != Pause.OFFLINE) {
                    lost = left()
                    fire("off:${t.toInt()}", Note("off", "Internet paused", "${e.city} went offline. Traffic stays blocked until you pick another exit.", listOf("Connect to best available" to "bestAvail", "Close" to "nclose"), proposed = true))
                }
                conn = newConn; pause = newPause
                // the internet pauses soon: little data left and nothing to renew with
                val a = left()
                if (conn == Conn.CONNECTED && a in 0.01..(chunk * 0.04) && wallet?.trafficLevel == Tunnel.Level.EMPTY)
                    fire("u6:$allocN", Note("u6", "Internet pauses soon", "About ${a.roundToInt()} MB of data left at ${e.city}.", listOf("Add data" to "addData", "Close" to "nclose")))
                val payFail = s.payment == Tunnel.Payment.FAILED
                if (payFail && !lastPayFail && conn == Conn.CONNECTED) setFlash(Tone.WARN, "Couldn’t buy more data yet.", "Your VPN balance may be too low. Gnosis VPN keeps trying.")
                lastPayFail = payFail
            }
        }
        if (conn == Conn.CONNECTED) connSec += dt
    }

    override fun connectFrac(): Float? {
        val s = session ?: return null
        val i = s.step ?: return null
        return ((i + 1).toFloat() / s.steps).coerceIn(0f, 1f)
    }
    override fun connectStep(): String = session?.stepName ?: if (tunnel().phase != "running") "Waiting for the network" else "Opening a route to ${ex(loc).country}"

    /* ---- throughput, from WireGuard's counters ---- */
    private fun syncRates(tn: Tunnel) {
        val c = tn.stats() ?: run { rx = null; lastCounters = null; return }
        val n = now(); val prev = lastCounters
        if (prev != null && (c.rxBytes != prev.rxBytes || c.txBytes != prev.txBytes || n - lastCountersAt > 5000)) {
            val sec = (n - lastCountersAt) / 1000.0
            if (sec > 0.5) {
                val down = (c.rxBytes - prev.rxBytes).coerceAtLeast(0) * 8 / 1e6 / sec
                val up = (c.txBytes - prev.txBytes).coerceAtLeast(0) * 8 / 1e6 / sec
                val r = rx
                rx = if (r == null) down to up else (r.first * .5 + down * .5) to (r.second * .5 + up * .5)
                lastCounters = c; lastCountersAt = n
            }
        } else if (prev == null) { lastCounters = c; lastCountersAt = n }
    }
    override fun rates(): Pair<Double, Double>? = if (isOn()) rx ?: (0.0 to 0.0) else null
    override fun sessionTotals(): Pair<Long, Long>? {
        val c = tunnel().stats() ?: return null; val s0 = sessionStart
        return if (s0 != null && c.rxBytes >= s0.rxBytes) (c.rxBytes - s0.rxBytes) to (c.txBytes - s0.txBytes) else c.rxBytes to c.txBytes
    }

    override fun sessionRows(): List<Pair<String, String>> {
        val s = session
        val rows = mutableListOf<Pair<String, String>>()
        s?.emptyInSec?.let { rows += "Data at this rate" to dur(max(1, (it / 60).toInt())) }
        s?.bytesLeft?.let { rows += "Data left here" to mb(it / 1e6) }
        s?.bytesSpent?.let { rows += "Used this session" to mb(it / 1e6) }
        if (conn == Conn.CONNECTED) rows += "Connected for" to dur((connSec / 60).toInt())
        return rows
    }

    override fun minsLeft(v: Double): Int = session?.emptyInSec?.let { max(1, (it / 60).toInt()) } ?: super.minsLeft(v)
    /** From the core's estimate only (the data left over the current rate); idle traffic has none. */
    override fun timeLeft(): String? = session?.emptyInSec?.let { sec -> if (sec >= 86_400) "more than a day" else "about " + dur(max(1, (sec / 60).toInt())) }

    /* ---- actions ---- */
    override fun runCheck() { nextCheck = Double.MAX_VALUE }

    override fun doConnect(id: String) {
        home(); sheet = null; fullMsg = null; flash = null; fq.clear(); offConfirm = false; better = null
        loc = id; conn = Conn.CONNECTING; pause = null; cStart = now(); cDur = 60_000
        val tn = tunnel()
        tn.locations.firstOrNull { it.id == id }?.let { tn.selectLocation(it) }
        if (tn.state == Tunnel.State.DISCONNECTED || tn.state == Tunnel.State.ERROR) hooks.connectVpn()
        save(); emit()
    }

    override fun disconnect() { super.disconnect(); hooks.disconnectVpn() }
    override fun onCancel() = hooks.disconnectVpn()
    override fun onTurnOff() = hooks.disconnectVpn()
    override fun onPayLockdownOff() = hooks.disconnectVpn()

    /** Prototype-only actions (scenarios, simulated events and settings the core decides itself). */
    private val ignored = setOf(
        "preset", "speed", "skip", "sess", "bal", "evOffline", "evDrop", "evTwo", "evUpdate", "evPay", "updNow", "updLater",
        "gatePaid", "gateRestart", "failShow", "syncSkip", "clearLog", "tgl", "togAR", "togP4", "arCheckGo", "arCheckNo", "arPastOn",
        "docs", "support", "logsUpload", "demo", "sysAO", "sysBlock", "go", "reset",
    )

    override fun act(a: String, d: Map<String, String>) {
        when (a) {
            in ignored -> return
            "renew" -> { sheet = null; if (conn == Conn.PAUSED || conn == Conn.IDLE) doConnect(loc) else emit(); return }   // retry: the core renews by itself
            "arOnGo", "arOffGo" -> { arIntro = true; super.act("sheetGo", d); save(); return }
            "funding", "gateFund" -> { hooks.openUrl(fundUrl()); if (gate) pay = t; emit(); return }
            "failRetry", "restart" -> { fail = null; syncT = 0.0; hooks.restartBackend(); emit(); return }
            "pick" -> { tunnel().refreshExits(); super.act(a, d); return }
        }
        super.act(a, d)
        save()
    }

    override fun address(): String = fundAddress ?: wallet?.address ?: ""
    override fun fundUrl(): String = FAUCET_URL
    override fun explorerUrl(): String = address().takeIf { ADDRESS.matches(it) }?.let { "$EXPLORER_URL$it" } ?: ""

    override fun fundingNeeds(): List<Triple<String, String, String>> {
        val w = wallet
        if (gate || w == null) {
            // "241.01 wxHOPR + 0.005 xDAI"
            val parts = need?.split("+")?.map { it.trim().split(" ") }.orEmpty()
            fun amount(token: String) = parts.firstOrNull { it.getOrNull(1) == token }?.getOrNull(0)?.let { tokens(BigDecimal(it)) } ?: "—"
            return listOf(Triple("wxHOPR", "Pays for your traffic", amount("wxHOPR")), Triple("xDAI", "Pays network fees", amount("xDAI")))
        }
        return listOf(
            Triple("wxHOPR", "Pays for your traffic · you have ${tokens(w.traffic)}", w.trafficDeficit?.let { tokens(it) } ?: "Any amount"),
            Triple("xDAI", "Pays network fees · you have ${tokens(w.gas)}", w.gasDeficit?.let { tokens(it) } ?: "Any amount"),
        )
    }

    override fun exitTag(e: Exit): String = e.id.removePrefix("0x").take(4).lowercase()
    override fun serverLoad(e: Exit): Int? = e.load?.let { (it * 100).roundToInt().coerceIn(0, 999) }
        ?: if (e.slots > 0) ((1 - e.spots.toDouble() / e.slots) * 100).roundToInt() else null

    override fun syncStep(): String? = tunnel().startup?.let { it.error ?: it.name } ?: "Determining available routes"
    override fun syncLen() = Double.MAX_VALUE
    override fun syncProgress(): Float {
        val s = tunnel().startup ?: return if (tunnel().phase == "running") 0.97f else 0.02f
        return ((s.step + 1).toFloat() / (s.steps + 1)).coerceIn(0.02f, 0.97f)
    }
    override fun endSync() {
        if (tunnel().phase != "running") return
        syncT = null
        if (startConnect && conn == Conn.IDLE && loc.isNotEmpty()) requestConnect(loc)
    }

    companion object {
        /** "Edinburgh, UK" → GB, "FR" → FR; "" when the place has no two-letter country. */
        fun countryCode(place: String): String {
            val c = place.substringAfterLast(",").trim().uppercase()
            return when { c == "UK" -> "GB"; c.length == 2 && c.all { it in 'A'..'Z' } -> c; else -> "" }
        }

        /** Whole tokens, short: 241.01, 0.005. */
        fun tokens(v: BigDecimal): String {
            val scale = if (v.abs() >= BigDecimal.ONE) 2 else 4
            return v.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }

        /** The Gnosis VPN funding site: pays from a wallet, or redeems a funding code, straight to the VPN address. */
        const val FAUCET_URL = "https://faucet.vpn.gnosis.eth.limo/"
        /** Addresses on Gnosis Chain's block explorer. */
        const val EXPLORER_URL = "https://gnosisscan.io/address/"
        private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")
    }
}
