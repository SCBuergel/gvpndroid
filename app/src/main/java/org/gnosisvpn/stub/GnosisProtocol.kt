package org.gnosisvpn.stub

import org.json.JSONObject

/**
 * The JSON contract with the Rust core (rust/gnosis_vpn-android): commands are the client's
 * `WorkerCommand` (serde tag "type", snake_case), replies its `Response`. Pure functions, so the
 * unit tests can check them against rust/gnosis_vpn-android/tests/protocol.json, which the Rust
 * tests check against the real client types.
 */
object GnosisProtocol {
    const val STATUS = """{"type":"status"}"""
    const val DISCONNECT = """{"type":"disconnect"}"""
    const val BALANCE = """{"type":"balance"}"""
    const val NERD_STATS = """{"type":"nerd_stats"}"""
    const val PROBING_BOOST = """{"type":"probing_boost"}"""

    fun connect(destinationId: String): String = JSONObject().put("type", "connect").put("destination", destinationId).toString()

    /** Connect reply types meaning "accepted, the tunnel will follow". */
    val CONNECT_ACCEPTED = setOf("connecting", "already_connected", "waiting_to_connect")

    /** An exit as the core reports it; [id] is the core's per-run destination id. */
    data class Destination(val id: String, val address: String, val name: String, val place: String, val health: Tunnel.ExitHealth? = null) {
        /** For the UI: keyed by the exit address, which stays the same across app starts. */
        fun toLocation() = Tunnel.Location(address, name, place, health)
    }

    /** The `data` object of a status reply, or null if [reply] is an error. */
    fun statusData(reply: String): JSONObject? = runCatching { JSONObject(reply).getJSONObject("data") }.getOrNull()

    fun runMode(data: JSONObject): String? = data.optJSONObject("run_mode")?.optString("type")

    /** Exits from a status `data` object, sorted by name. */
    fun destinations(data: JSONObject): List<Destination> {
        val arr = data.optJSONArray("destinations") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i).let { it.optJSONObject("destination") ?: it }
            val meta = d.optJSONObject("meta")
            val other = meta?.optJSONObject("other")
            val geo = d.optJSONObject("geo")
            val place = listOfNotNull(
                other?.optString("location")?.takeIf { it.isNotEmpty() },
                other?.optString("flag")?.takeIf { it.isNotEmpty() } ?: geo?.optString("country")?.takeIf { it.isNotEmpty() },
            ).joinToString(", ")
            val name = meta?.optString("name")?.takeIf { it.isNotEmpty() } ?: d.optString("id")
            val cc = geo?.optString("country")?.takeIf { it.length == 2 }
            Destination(d.optString("id"), d.optString("address"), name, place, health(arr.getJSONObject(i).optJSONObject("route_health"), cc))
        }.sortedBy { it.name }
    }

    /** An exit's route health and last quick check (DestinationStatus.route_health). */
    fun health(rh: JSONObject?, countryCode: String?): Tunnel.ExitHealth {
        if (rh == null) return Tunnel.ExitHealth(countryCode)
        val state = rh.optJSONObject("state")?.optString("type")
        val walk = rh.optJSONObject("walk")?.takeIf { it.optString("type") == "paths" }
        val qp = rh.optJSONObject("quick_probe")
        val check = when (qp?.optString("type")) {
            "checked" -> qp
            "checking" -> qp.optJSONObject("last")
            else -> null
        }
        val load = check?.optJSONObject("load")
        val slots = load?.optJSONObject("slots")
        val avg = load?.optJSONObject("load_avg")
        val nproc = avg?.optInt("nproc", 0) ?: 0
        return Tunnel.ExitHealth(
            countryCode = countryCode,
            reachable = state == "routable",
            unrecoverable = state == "unrecoverable",
            pingMs = check?.takeIf { it.has("status_rtt") }?.optLong("status_rtt")?.toInt(),
            checkedAtMs = check?.optLong("checked_at")?.takeIf { it > 0 },
            slotsTotal = slots?.optInt("total"),
            slotsFree = slots?.optInt("available"),
            load = if (avg != null && nproc > 0) avg.optDouble("one") / nproc else null,
            pathValue = walk?.optDouble("best_value")?.takeIf { !it.isNaN() },
            firstRelays = walk?.optInt("distinct_first_relays"),
            checking = qp?.optString("type") == "checking",
            problem = rh.optString("last_error").takeIf { it.isNotEmpty() && it != "null" }
                ?: qp?.takeIf { it.optString("type") == "failed" }?.optString("error"),
            why = if (state == "routable") null else whyNot(rh),
        )
    }

    /**
     * Why an exit can't be reached, in words, from its route health: the full DestinationStatus
     * route_health (state, walk, last_error, quick_probe) or just a RouteHealthState (a connect
     * reply). The same wording as gvpndows.
     */
    fun whyNot(rh: JSONObject?): String {
        val st = rh?.optJSONObject("state") ?: rh?.takeIf { it.has("type") }
        val reason = st?.optJSONObject("reason")
        val walk = rh?.optJSONObject("walk")
        fun str(o: JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotEmpty() && it != "null" }
        val qp = rh?.optJSONObject("quick_probe")
        val text = when (st?.optString("type")) {
            "unrecoverable" -> when (val t = reason?.optString("type")) {
                "not_allowed" -> "This exit needs a route type the app does not allow."
                "incompatible_api_version" -> "This exit runs a version this app cannot talk to."
                "cannot_open_session" -> "The exit refused the session: ${str(reason, "error") ?: "no reason given"}."
                else -> "The exit cannot be used ($t)."
            }
            "not_routable" -> when (walk?.optString("type")) {
                "not_announced" -> "The exit has not announced itself on the network, so there is no way to reach it."
                "no_path" -> "No relay path to the exit right now (${walk.optInt("forward_count")} forward, ${walk.optInt("return_count")} return)."
                else -> "There is no route to this exit right now."
            }
            else -> "The exit did not answer the connection attempt."
        }
        val last = str(rh, "last_error") ?: str(qp?.optJSONObject("last"), "error") ?: str(qp, "error")
        return if (last != null) "$text Last error: $last" else text
    }

    /**
     * The core gave up on a connect attempt: the user still wants the VPN, no Connect is being
     * sent, the connection fell from connecting/reconnecting to disconnected, and the core has no
     * target any more. While it switches exits, probes or waits for a route it keeps the target.
     */
    fun coreGaveUp(wantConnected: Boolean, connectLoop: Boolean, before: String, now: String, coreTarget: String?): Boolean =
        wantConnected && !connectLoop && now == "disconnected" && before in setOf("connecting", "reconnecting") && coreTarget.isNullOrEmpty()

    /** Why the core refused a connect, in words, or null when it took it (connecting, waiting, already connected). */
    fun connectRefusal(reply: String?): String? {
        if (reply.isNullOrEmpty()) return "The Gnosis VPN core did not answer."
        val o = runCatching { JSONObject(reply) }.getOrNull() ?: return null
        str(o, "error")?.let { return it }
        val d = statusData(reply) ?: o
        return if (d.optString("type") == "unable_to_connect") whyNot(d.optJSONObject("route_health")) else null
    }

    private fun str(o: JSONObject, k: String) = o.takeIf { it.has(k) && !it.isNull(k) }?.optString(k)?.takeIf { it.isNotEmpty() }

    /** The connect phases in order (ConnectPhase), with readable names. */
    val CONNECT_PHASES = listOf(
        "init" to "Starting", "gather_peer_ips" to "Finding relays", "killswitch_lockdown" to "Blocking traffic outside the VPN",
        "generating_wg" to "Creating your keys", "register_wg" to "Registering with the exit", "opening_ping" to "Opening the route",
        "establish_wg_tunnel" to "Securing the tunnel", "verify_ping" to "Checking the tunnel", "adjust_to_main" to "Moving to the main route",
        "connection_established" to "Connected",
    )

    private val CARRYING = setOf("verify_ping", "adjust_to_main", "connection_established")

    /** The session from a status `data` object (connection), or null while disconnected. */
    fun session(data: JSONObject): Tunnel.Session? {
        val c = data.optJSONObject("connection") ?: return null
        val type = c.optString("type")
        if (type == "disconnected" || type.isEmpty()) return null
        val phase = c.optJSONObject("phase")?.optString("type")
        val idx = CONNECT_PHASES.indexOfFirst { it.first == phase }.takeIf { it >= 0 }
        val pix = c.optJSONObject("pix")
        val stall = c.optJSONObject("stall")
        fun long(o: JSONObject?, k: String) = o?.takeIf { it.has(k) && !it.isNull(k) }?.optLong(k)
        return Tunnel.Session(
            exitId = c.optString("destination"), sinceMs = c.optLong("since"),
            // From verify_ping on the tunnel and its routes carry traffic; the core only checks the
            // exit's ping and tunes the session. Shown as connected (as gvpndows does).
            step = if (type == "connected" || (type == "connecting" && phase in CARRYING)) CONNECT_PHASES.size - 1 else idx, steps = CONNECT_PHASES.size, stepName = idx?.let { CONNECT_PHASES[it].second },
            pingMs = long(c, "tunnel_ping_rtt")?.toInt(),
            chunkBytes = long(pix, "chunk_bytes"), bytesLeft = long(pix, "bytes_left"), bytesSpent = long(pix, "bytes_spent"),
            bytesBought = long(pix, "bytes_bought"), cost = pix?.optString("cost")?.let { token(it) },
            emptyInSec = long(pix, "empty_in")?.let { it / 1000 },
            payment = when (pix?.optJSONObject("phase")?.optString("type")) {
                "free" -> Tunnel.Payment.FREE; "payment_pending" -> Tunnel.Payment.PENDING
                "payment_failed" -> Tunnel.Payment.FAILED; "funded" -> Tunnel.Payment.FUNDED; else -> null
            },
            stalled = stall != null, awaitingPayment = stall != null && !stall.isNull("awaiting_payment_since") && stall.has("awaiting_payment_since"),
            reconnecting = type == "reconnecting",
        )
    }

    /** The start-up steps (run mode and warmup stage), for the progress bar. */
    private val STARTUP = listOf(
        "init" to "Starting", "deploying_safe" to "Setting up your node’s Safe", "initializing" to "Starting",
        "validating_config" to "Checking the configuration", "identifying_node" to "Identifying your node",
        "connecting_blockchain" to "Connecting to Gnosis Chain", "creating_node" to "Creating your node", "starting_node" to "Starting your node",
        "uninitialized" to "Starting your node", "waiting_for_funds" to "Waiting for funds", "checking_balance" to "Checking your balance",
        "validating_network_config" to "Checking the network", "checking_onchain_address" to "Checking your node on-chain",
        "registering_safe" to "Registering your Safe", "announcing_node" to "Announcing your node", "awaiting_key_binding" to "Binding your keys",
        "initializing_services" to "Looking for other node runners", "ready" to "Determining available routes", "running" to "Determining available routes",
    )

    /** Start-up progress from a status `data` object, or null once running (or while waiting for funds). */
    fun startup(data: JSONObject): Tunnel.Startup? {
        val mode = data.optJSONObject("run_mode") ?: return null
        val key = when (val t = mode.optString("type")) {
            "warmup" -> mode.optJSONObject("stage")?.optString("type") ?: "initializing"
            "running", "preparing_safe" -> return null
            "not_running", "shutdown", "restarting" -> "init"
            else -> t
        }
        val i = STARTUP.indexOfFirst { it.first == key }.coerceAtLeast(0)
        return Tunnel.Startup(i, STARTUP.size, STARTUP[i].second, issue(mode.optJSONObject("issue")))
    }

    /**
     * A start-up step that keeps failing (IPC v2 StartupIssue: kind, message, since), said for
     * people; null when there is none. The core keeps retrying by itself.
     */
    fun issue(o: JSONObject?): String? {
        o ?: return null
        return when (o.optJSONObject("kind")?.optString("type")) {
            "unreachable" -> "Can’t reach Gnosis Chain. Check your internet connection; retrying."
            "service_unavailable" -> "The Gnosis Chain service is busy. Retrying."
            "incompatible" -> "This version of Gnosis VPN needs an update."
            else -> o.optString("message").takeIf { it.isNotEmpty() }?.let { "Start-up problem: $it. Retrying." } ?: "Start-up problem. Retrying."
        }
    }

    /** A failure the core reports while running (the node failed), or null. */
    fun failure(data: JSONObject): String? {
        val mode = data.optJSONObject("run_mode") ?: return null
        val hs = mode.optJSONObject("hopr_status")?.optString("type")
        if (mode.optString("type") == "running" && hs == "failed") return "Your node stopped"
        if (mode.optString("type") == "warmup" && mode.optJSONObject("stage")?.optString("type") == "failed") return "Your node could not start"
        return null
    }

    /** Wallet from a balance reply (BalanceResponse::Available), or null. */
    fun wallet(reply: String): Tunnel.Wallet? {
        val d = statusData(reply)?.takeIf { it.optString("type") == "available" } ?: return null
        val fs = d.optJSONObject("funding_status")
        fun level(k: String) = when (fs?.optJSONObject(k)?.optString("type")) { "low" -> Tunnel.Level.LOW; "empty" -> Tunnel.Level.EMPTY; else -> Tunnel.Level.GOOD }
        fun opt(o: JSONObject?, k: String) = o?.takeIf { it.has(k) && !it.isNull(k) }?.optString(k)?.let { token(it) }
        return Tunnel.Wallet(
            address = d.optJSONObject("info")?.optString("node_address") ?: "",
            traffic = token(d.optString("safe")), gas = token(d.optString("node")),
            trafficLevel = level("traffic"), gasLevel = level("gas"),
            trafficDeficit = opt(fs, "wxhopr_deficit"), gasDeficit = opt(fs, "xdai_deficit"),
        )
    }

    /** WireGuard counters from a nerd_stats reply: rx, tx bytes. */
    fun rxTx(reply: String): Pair<Long, Long>? {
        val cur = statusData(reply)?.optJSONObject("conn")?.optJSONObject("wg_stats")?.optJSONObject("current") ?: return null
        return cur.optLong("rx_bytes") to cur.optLong("tx_bytes")
    }

    fun chunkBytes(data: JSONObject): Long? = data.takeIf { it.has("pix_chunk_bytes") && !it.isNull("pix_chunk_bytes") }?.optLong("pix_chunk_bytes")

    /** Recently used exits (destination ids), newest first. */
    fun recent(data: JSONObject): List<String> = data.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    /** pix_strategy.price_per_byte from the client config ("9 000 000 000 wei wxHOPR"), in whole wxHOPR. */
    fun pricePerByte(config: String): java.math.BigDecimal? {
        val m = Regex("""price_per_byte\s*=\s*"([0-9 _]+)\s*wei""").find(config) ?: return null
        return m.groupValues[1].replace(" ", "").replace("_", "").toBigDecimalOrNull()?.movePointLeft(18)?.takeIf { it.signum() > 0 }
    }

    fun token(wei: String?): java.math.BigDecimal = wei?.toBigDecimalOrNull()?.movePointLeft(18) ?: java.math.BigDecimal.ZERO

    /** What a node in run mode preparing_safe waits for, or null in any other run mode. */
    fun fundingRequest(data: JSONObject): Tunnel.FundingRequest? {
        val mode = data.optJSONObject("run_mode") ?: return null
        if (mode.optString("type") != "preparing_safe") return null
        val rec = mode.optJSONObject("balance_recommendation")
        val need = "${tokens(rec?.optString("wxhopr"))} wxHOPR + ${tokens(rec?.optString("xdai"))} xDAI"
        return Tunnel.FundingRequest(mode.optString("node_address"), need)
    }

    /** Wei (18 decimals) as a plain decimal string. */
    fun tokens(wei: String?): String =
        wei?.toBigDecimalOrNull()?.movePointLeft(18)?.stripTrailingZeros()?.toPlainString() ?: "?"
}
