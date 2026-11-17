package org.gnosisvpn.stub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Gnosis VPN backend: the Rust client core (HOPR session to an exit, WireGuard inside it).
 *
 * Settings live in app-private storage (noBackupFilesDir), never in the APK or the repo:
 * - gnosis.properties: blokli_url, network, destination (an exit address or destination id)
 * - gnosis.toml: the client config (v7), with geoip_db and curvy_zk_dir pointing at files
 *   under noBackupFilesDir/gnosis-share
 * The node identity and its state are kept in noBackupFilesDir/gnosis.
 *
 * The core starts with the app ([prestart]) so it can sync, find peers and probe routes before
 * the user taps Connect; a status poller keeps [destinations] current for the location picker.
 */
class GnosisTunnel(private val context: Context) : Tunnel {
    /** An exit the core knows about, for the location picker. */
    data class Destination(val id: String, val address: String, val name: String, val place: String) {
        val label get() = if (place.isEmpty()) name else "$name — $place"
    }

    private val dir = context.noBackupFilesDir
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Tunnel.Listener>()
    @Volatile private var coreStarted = false
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunParams: String? = null
    @Volatile private var wantConnected = false
    @Volatile private var status: JSONObject? = null
    /** Where to connect (exit address or destination id); read by the connect loop on every pass. */
    @Volatile private var target: String? = null

    @Volatile var destinations: List<Destination> = emptyList()
        private set

    @Volatile
    override var state: Tunnel.State = Tunnel.State.DISCONNECTED
        private set

    /** Starts the core without connecting (at app start), so connecting later is quicker. */
    fun prestart() {
        worker.execute {
            try {
                startCoreOnce(loadProps())
            } catch (e: Exception) {
                LogBuffer.log("gnosis: core start failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun connect() {
        if (state == Tunnel.State.CONNECTING || state == Tunnel.State.CONNECTED) return
        wantConnected = true
        set(Tunnel.State.CONNECTING, null)
        worker.execute {
            try {
                val props = loadProps()
                startCoreOnce(props)
                target = props.getProperty("destination") ?: error("gnosis.properties has no destination")
                connectWhenReady()
            } catch (e: Exception) {
                set(Tunnel.State.ERROR, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        wantConnected = false
        if (state == Tunnel.State.DISCONNECTED) return
        set(Tunnel.State.DISCONNECTING, null)
        worker.execute {
            disconnectCore()
            set(Tunnel.State.DISCONNECTED, null)
        }
    }

    /** Remembers [destination] (id or address) and, when connected or connecting, moves there. */
    fun selectDestination(destination: Destination) {
        saveProp("destination", destination.address)
        LogBuffer.log("gnosis: location set to ${destination.label}")
        target = destination.address
        if (state == Tunnel.State.CONNECTED || state == Tunnel.State.CONNECTING) {
            // Off the worker: it may be inside the connect loop, which picks up the new target.
            Thread {
                disconnectCore()
                wantConnected = true
                set(Tunnel.State.CONNECTING, "switching to ${destination.name}")
                worker.execute { connectWhenReady() }
            }.start()
        }
    }

    /** The destination the settings point at (by address or id), if the core knows it. */
    fun selectedDestination(): Destination? {
        val wanted = runCatching { loadProps().getProperty("destination") }.getOrNull() ?: return null
        return destinations.firstOrNull { it.address.equals(wanted, ignoreCase = true) || it.id == wanted }
    }

    /** Applies [level] to the core's log filter (and to the next start). */
    fun applyLogLevel(level: LogLevel) {
        runCatching { Os.setenv("RUST_LOG", level.coreFilter, true) }
        if (coreStarted) GnosisNative.setLogFilter(level.coreFilter)?.let { LogBuffer.log("log filter rejected: $it") }
    }

    override fun stats(): Tunnel.Stats? = null

    override fun addListener(listener: Tunnel.Listener) { listeners += listener }
    override fun removeListener(listener: Tunnel.Listener) { listeners -= listener }

    private fun disconnectCore() {
        if (coreStarted) LogBuffer.log("gnosis: disconnect -> ${short(GnosisNative.command("""{"type":"disconnect"}"""))}")
        closeTun()
    }

    private fun propsFile() = File(dir, "gnosis.properties")

    private fun loadProps() = Properties().apply {
        val f = propsFile()
        require(f.exists()) { "missing ${f.path}" }
        f.inputStream().use { load(it) }
    }

    private fun saveProp(key: String, value: String) {
        val props = loadProps().apply { setProperty(key, value) }
        propsFile().outputStream().use { props.store(it, "Gnosis VPN device settings") }
    }

    @Synchronized
    private fun startCoreOnce(props: Properties) {
        if (coreStarted) return
        GnosisNative.loadError?.let { error("native core not available: $it") }
        val config = File(dir, "gnosis.toml").readText()
        val data = File(dir, "gnosis").apply { mkdirs() }
        val cache = File(context.cacheDir, "gnosis").apply { mkdirs() }
        val level = LogLevel.get(context)
        // Read by the core's logger when it initialises (in initAndroid).
        runCatching { Os.setenv("RUST_LOG", level.coreFilter, true) }
        LogBuffer.log("gnosis: starting core (${GnosisNative.version()}), log level ${level.name.lowercase()}")
        GnosisNative.initAndroid(context.applicationContext)
        GnosisNative.startCore(
            data.path, cache.path, config,
            props.getProperty("blokli_url") ?: error("gnosis.properties has no blokli_url"),
            props.getProperty("network") ?: error("gnosis.properties has no network"),
        )?.let { error("startCore: $it") }
        coreStarted = true
        Thread(::eventLoop, "gnosis-events").apply { isDaemon = true }.start()
        Thread(::statusLoop, "gnosis-status").apply { isDaemon = true }.start()
    }

    /**
     * Polls the core's status: keeps [destinations] current, logs run-mode and connection changes
     * (every phase in verbose/debug), shows funding needs, and writes the full status to
     * cacheDir/gnosis-status.json for debugging.
     */
    private fun statusLoop() {
        var lastRun = ""
        var lastConn = ""
        var lastDests = ""
        while (coreStarted) {
            val raw = GnosisNative.command("""{"type":"status"}""")
            runCatching { File(context.cacheDir, "gnosis-status.json").writeText(raw) }
            val data = runCatching { JSONObject(raw).getJSONObject("data") }.getOrNull()
            if (data != null) {
                status = data
                val mode = data.optJSONObject("run_mode")
                val runMode = mode?.optString("type") ?: "?"
                if (runMode != lastRun) {
                    LogBuffer.log("gnosis: run mode $runMode")
                    lastRun = runMode
                    if (runMode == "preparing_safe" && mode != null) logFundingNeed(mode)
                }
                if (runMode != "preparing_safe") FundingRequest.current = null
                val verbose = LogLevel.get(context) != LogLevel.NORMAL
                val connObj = data.optJSONObject("connection")
                val conn = if (verbose) short(connObj?.toString() ?: "?", 400) else connObj?.optString("type") ?: "?"
                if (conn != lastConn) { LogBuffer.log("gnosis: connection $conn"); lastConn = conn }
                destinations = parseDestinations(data)
                val names = destinations.joinToString(", ") { it.label }
                if (names != lastDests && names.isNotEmpty()) { LogBuffer.log("gnosis: ${destinations.size} locations: $names"); lastDests = names }
            }
            Thread.sleep(if (state == Tunnel.State.CONNECTING) 3_000 else 10_000)
        }
    }

    private fun parseDestinations(data: JSONObject): List<Destination> {
        val arr = data.optJSONArray("destinations") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i).let { it.optJSONObject("destination") ?: it }
            val meta = d.optJSONObject("meta")
            val other = meta?.optJSONObject("other")
            val geo = d.optJSONObject("geo")
            val place = listOfNotNull(
                other?.optString("location")?.takeIf { it.isNotEmpty() },
                (other?.optString("flag")?.takeIf { it.isNotEmpty() } ?: geo?.optString("country")?.takeIf { it.isNotEmpty() }),
            ).joinToString(", ")
            Destination(d.optString("id"), d.optString("address"), meta?.optString("name")?.takeIf { it.isNotEmpty() } ?: d.optString("id"), place)
        }.sortedBy { it.label }
    }

    /** Retries Connect until the core accepts it, for the current [target]. */
    private fun connectWhenReady() {
        var waitedFor = ""
        while (wantConnected && state == Tunnel.State.CONNECTING) {
            val wanted = target ?: return
            val data = status
            val runMode = data?.optJSONObject("run_mode")?.optString("type")
            val id = destinations.firstOrNull { it.address.equals(wanted, ignoreCase = true) || it.id == wanted }?.id
            if (runMode == "running" && id != null) {
                val reply = GnosisNative.command(JSONObject().put("type", "connect").put("destination", id).toString())
                val type = runCatching { JSONObject(reply).getJSONObject("data").getString("type") }.getOrDefault("?")
                LogBuffer.log("gnosis: connect $id -> $type")
                if (type in setOf("connecting", "already_connected", "waiting_to_connect")) return
                LogBuffer.log("gnosis: connect reply ${short(reply, 600)}")
            } else {
                val why = if (runMode != "running") "core is ${runMode ?: "starting"}" else "location $wanted not discovered yet"
                if (why != waitedFor) { LogBuffer.log("gnosis: waiting to connect: $why"); waitedFor = why }
            }
            Thread.sleep(3_000)
        }
    }

    /** A new node waits here until its address is funded; show what to send where. */
    private fun logFundingNeed(mode: JSONObject) {
        val rec = mode.optJSONObject("balance_recommendation")
        fun tokens(wei: String?) = wei?.toBigDecimalOrNull()?.movePointLeft(18)?.stripTrailingZeros()?.toPlainString() ?: "?"
        val need = "${tokens(rec?.optString("wxhopr"))} wxHOPR + ${tokens(rec?.optString("xdai"))} xDAI"
        LogBuffer.log("gnosis: FUNDING NEEDED for node ${mode.optString("node_address")}: $need " +
            "(has ${tokens(mode.optString("node_wxhopr"))} wxHOPR, ${tokens(mode.optString("node_xdai"))} xDAI)")
        FundingRequest.current = FundingRequest(mode.optString("node_address"), need)
    }

    /** Core events (tun requests) and the core's log lines for the screen. */
    private fun eventLoop() {
        while (true) {
            GnosisNative.pollLogs()?.lineSequence()?.forEach { LogBuffer.logCore(short(it, 400)) }
            val raw = GnosisNative.pollEvent()
            if (raw == null) { Thread.sleep(200); continue }
            val event = JSONObject(raw)
            if (LogLevel.get(context) != LogLevel.NORMAL) LogBuffer.log("gnosis event: ${short(raw, 300)}")
            when (event.optString("type")) {
                "setup_tunnel" -> setupTunnel(event)
                "teardown" -> {
                    // Keep the tun across the core's reconnects; it is closed on disconnect.
                    if (state == Tunnel.State.CONNECTED) set(Tunnel.State.CONNECTING, "core is reconnecting")
                }
                "core_error" -> { coreStarted = false; set(Tunnel.State.ERROR, event.optString("error")) }
                "core_stopped" -> { coreStarted = false; closeTun(); set(Tunnel.State.DISCONNECTED, "core stopped") }
            }
        }
    }

    private fun setupTunnel(event: JSONObject) {
        val id = event.getLong("request_id")
        val params = "${event.getString("address")}|${event.getInt("mtu")}|${event.optString("dns")}"
        try {
            val current = tun
            if (current != null && params == tunParams) {
                // In-place reconnect (new HOPR session, same tunnel address): hand the core the same
                // tun so apps' open connections survive.
                GnosisNative.tunReady(id, current.fd)?.let { error(it) }
                set(Tunnel.State.CONNECTED, "reconnected in place, tun kept")
                return
            }
            val service = GnosisVpnService.obtain(context)
            closeTun()
            val pfd = service.establish(event.getString("address"), event.getInt("mtu"), event.optString("dns").ifEmpty { null })
                ?: error("establish() returned null (VPN permission missing?)")
            tun = pfd
            tunParams = params
            GnosisNative.tunReady(id, pfd.fd)?.let { error(it) }
            set(Tunnel.State.CONNECTED, "tun ${event.getString("address")} mtu ${event.getInt("mtu")}")
        } catch (e: Exception) {
            LogBuffer.log("gnosis: tun setup failed: ${e.javaClass.simpleName}: ${e.message}")
            GnosisNative.tunReady(id, -1)
            set(Tunnel.State.ERROR, "tun setup failed")
        }
    }

    private fun closeTun() {
        tun?.let { runCatching { it.close() } }
        tun = null
        tunParams = null
        GnosisVpnService.stop(context)
    }

    private fun set(s: Tunnel.State, detail: String?) {
        state = s
        main.post { listeners.forEach { it.onStateChanged(s, detail) } }
    }

    private fun short(s: String, max: Int = 200) = if (s.length <= max) s else s.take(max) + "…(${s.length})"

    companion object {
        /** The Gnosis backend is used when its settings file exists. */
        fun isConfigured(context: Context) = File(context.noBackupFilesDir, "gnosis.properties").exists()
    }
}

/** What a new node waits for; shown on screen with a QR code of the address while it waits. */
data class FundingRequest(val address: String, val need: String) {
    companion object {
        @Volatile var current: FundingRequest? = null
    }
}
