package org.gnosisvpn.stub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Gnosis VPN backend: the Rust client core (HOPR session to an exit, WireGuard inside it).
 *
 * Settings live in app-private storage (noBackupFilesDir), never in the APK or the repo:
 * - gnosis.properties: blokli_url, network, destination (the exit's destination id)
 * - gnosis.toml: the client config (v7), with geoip_db and curvy_zk_dir pointing at files
 *   under noBackupFilesDir/gnosis-share
 * The node identity and its state are kept in noBackupFilesDir/gnosis.
 */
class GnosisTunnel(private val context: Context) : Tunnel {
    private val dir = context.noBackupFilesDir
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Tunnel.Listener>()
    @Volatile private var coreStarted = false
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunParams: String? = null
    @Volatile private var wantConnected = false

    @Volatile
    override var state: Tunnel.State = Tunnel.State.DISCONNECTED
        private set

    override fun connect() {
        if (state == Tunnel.State.CONNECTING || state == Tunnel.State.CONNECTED) return
        wantConnected = true
        set(Tunnel.State.CONNECTING, null)
        worker.execute {
            try {
                val props = loadProps()
                startCoreOnce(props)
                connectWhenReady(props.getProperty("destination") ?: error("gnosis.properties has no destination"))
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
            if (coreStarted) LogBuffer.log("gnosis: disconnect -> ${short(GnosisNative.command("""{"type":"disconnect"}"""))}")
            closeTun()
            set(Tunnel.State.DISCONNECTED, null)
        }
    }

    override fun stats(): Tunnel.Stats? = null

    override fun addListener(listener: Tunnel.Listener) { listeners += listener }
    override fun removeListener(listener: Tunnel.Listener) { listeners -= listener }

    private fun loadProps() = Properties().apply {
        val f = File(dir, "gnosis.properties")
        require(f.exists()) { "missing ${f.path}" }
        f.inputStream().use { load(it) }
    }

    private fun startCoreOnce(props: Properties) {
        if (coreStarted) return
        GnosisNative.loadError?.let { error("native core not available: $it") }
        val config = File(dir, "gnosis.toml").readText()
        val data = File(dir, "gnosis").apply { mkdirs() }
        val cache = File(context.cacheDir, "gnosis").apply { mkdirs() }
        LogBuffer.log("gnosis: starting core (${GnosisNative.version()})")
        GnosisNative.initAndroid(context.applicationContext)
        GnosisNative.startCore(
            data.path, cache.path, config,
            props.getProperty("blokli_url") ?: error("gnosis.properties has no blokli_url"),
            props.getProperty("network") ?: error("gnosis.properties has no network"),
        )?.let { error("startCore: $it") }
        coreStarted = true
        Thread(::eventLoop, "gnosis-events").apply { isDaemon = true }.start()
    }

    /**
     * Retries Connect until the core accepts it, logging status meanwhile (the node may still
     * sync, deploy its Safe or wait for funds). [wanted] is a destination id or an exit address
     * (0x…), resolved against the destinations the core reports. The full status is written to
     * cacheDir/gnosis-status.json for debugging.
     */
    private fun connectWhenReady(wanted: String) {
        var lastSummary = ""
        var lastDestinations = ""
        while (wantConnected && state == Tunnel.State.CONNECTING) {
            val raw = GnosisNative.command("""{"type":"status"}""")
            runCatching { File(context.cacheDir, "gnosis-status.json").writeText(raw) }
            val data = runCatching { JSONObject(raw).getJSONObject("data") }.getOrNull()
            if (data == null) {
                LogBuffer.log("gnosis: status ${short(raw, 300)}")
                Thread.sleep(10_000)
                continue
            }
            val runMode = data.optJSONObject("run_mode")?.optString("type") ?: "?"
            val conn = data.optJSONObject("connection")?.optString("type") ?: "?"
            val dests = data.optJSONArray("destinations")
            val summary = "run_mode=$runMode connection=$conn destinations=${dests?.length() ?: 0}"
            if (summary != lastSummary) {
                LogBuffer.log("gnosis: $summary")
                lastSummary = summary
                if (runMode == "preparing_safe") logFundingNeed(data.getJSONObject("run_mode"))
            }
            if (runMode != "preparing_safe") FundingRequest.current = null
            val list = (0 until (dests?.length() ?: 0)).map { i ->
                val d = dests!!.getJSONObject(i).let { it.optJSONObject("destination") ?: it }
                Triple(d.optString("id"), d.optString("address"), d.optJSONObject("meta")?.toString() ?: "")
            }
            val listText = list.joinToString("; ") { "${it.first} ${it.second} ${it.third}" }
            if (listText.isNotEmpty() && listText != lastDestinations) {
                LogBuffer.log("gnosis: destinations: ${short(listText, 1500)}")
                lastDestinations = listText
            }
            val id = if (wanted.startsWith("0x")) list.firstOrNull { it.second.equals(wanted, ignoreCase = true) }?.first else wanted
            if (runMode == "running" && id != null) {
                val reply = GnosisNative.command(JSONObject().put("type", "connect").put("destination", id).toString())
                val type = runCatching { JSONObject(reply).getJSONObject("data").getString("type") }.getOrDefault("?")
                LogBuffer.log("gnosis: connect $id -> $type")
                if (type in setOf("connecting", "already_connected", "waiting_to_connect")) return
                LogBuffer.log("gnosis: connect reply ${short(reply, 600)}")
            } else if (runMode == "running") {
                LogBuffer.log("gnosis: destination $wanted not (yet) among the discovered ones")
            }
            Thread.sleep(10_000)
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

    private fun eventLoop() {
        while (true) {
            val raw = GnosisNative.pollEvent()
            if (raw == null) { Thread.sleep(200); continue }
            val event = JSONObject(raw)
            LogBuffer.log("gnosis event: ${short(raw, 300)}")
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
                LogBuffer.log("gnosis: reconnected in place, tun kept")
                set(Tunnel.State.CONNECTED, null)
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
