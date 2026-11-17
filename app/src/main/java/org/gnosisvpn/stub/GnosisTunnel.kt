package org.gnosisvpn.stub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Gnosis VPN backend: the Rust client core (HOPR session to an exit, WireGuard inside it).
 * One instance per process (see App), because the core and its event queue exist once.
 *
 * Settings live in app-private storage (noBackupFilesDir), never in the APK or the repo:
 * - gnosis.properties: blokli_url, network, destination (an exit address or destination id)
 * - gnosis.toml: the client config (v7), with geoip_db and curvy_zk_dir pointing at files
 *   under noBackupFilesDir/gnosis-share (GnosisSetup)
 * The node identity and its state are kept in noBackupFilesDir/gnosis.
 *
 * Lifecycle: [prestart] starts the core at app start (syncing, peering and route probing take a
 * while); [connect]/[disconnect] open and close the tunnel; [shutdown] stops the core when the app
 * switches to another backend. Once a tun exists it is kept until the user disconnects: the
 * core's reconnects and location switches reuse or replace it, so traffic never leaves outside
 * the VPN in between (it is dropped instead).
 */
class GnosisTunnel(private val context: Context) : Tunnel {
    private val dir = context.noBackupFilesDir
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Tunnel.Listener>()
    private val loopsStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var coreRunning = false
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunParams: String? = null
    @Volatile private var wantConnected = false
    @Volatile private var status: org.json.JSONObject? = null
    /** Where to connect (exit address or destination id); read by the connect loop on every pass. */
    @Volatile private var target: String? = null
    @Volatile private var destinations: List<GnosisProtocol.Destination> = emptyList()

    @Volatile
    override var state: Tunnel.State = Tunnel.State.DISCONNECTED
        private set

    @Volatile
    override var fundingRequest: Tunnel.FundingRequest? = null
        private set

    override val locations: List<Tunnel.Location> get() = destinations.map { it.toLocation() }

    override val selectedLocation: Tunnel.Location?
        get() {
            val wanted = runCatching { loadProps().getProperty("destination") }.getOrNull() ?: return null
            return find(wanted)?.toLocation()
        }

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
            closeTun(stopService = true)
            set(Tunnel.State.DISCONNECTED, null)
        }
    }

    override fun selectLocation(location: Tunnel.Location) {
        saveProp("destination", location.id)
        target = location.id
        LogBuffer.log("gnosis: location set to ${location.label}")
        if (state == Tunnel.State.CONNECTED || state == Tunnel.State.CONNECTING) {
            // The tun stays up while the core moves: traffic is dropped, not sent around the VPN.
            // Off the worker, which may be inside the connect loop (it picks up the new target).
            Thread {
                disconnectCore()
                wantConnected = true
                set(Tunnel.State.CONNECTING, "switching to ${location.name}")
                worker.execute { connectWhenReady() }
            }.start()
        }
    }

    override fun setLogLevel(level: LogLevel) {
        if (coreRunning) GnosisNative.setLogFilter(level.coreFilter)?.let { LogBuffer.log("log filter rejected: $it") }
    }

    override fun shutdown() {
        wantConnected = false
        val wasConnected = state != Tunnel.State.DISCONNECTED
        worker.execute {
            // Only when connected: a core still starting answers commands slowly (up to 60 s).
            if (wasConnected) disconnectCore()
            closeTun(stopService = true)
            if (coreRunning) {
                GnosisNative.stopCore()
                coreRunning = false
                LogBuffer.log("gnosis: core stopped")
            }
            fundingRequest = null
            destinations = emptyList()
            set(Tunnel.State.DISCONNECTED, null)
        }
    }

    override fun stats(): Tunnel.Stats? = null

    override fun addListener(listener: Tunnel.Listener) { listeners += listener }
    override fun removeListener(listener: Tunnel.Listener) { listeners -= listener }

    private fun find(wanted: String) = destinations.firstOrNull { it.address.equals(wanted, ignoreCase = true) || it.id == wanted }

    private fun disconnectCore() {
        if (coreRunning) LogBuffer.log("gnosis: disconnect -> ${short(GnosisNative.command(GnosisProtocol.DISCONNECT))}")
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
        if (coreRunning) return
        GnosisNative.loadError?.let { error("native core not available: $it") }
        val config = File(dir, "gnosis.toml").readText()
        val data = File(dir, "gnosis").apply { mkdirs() }
        val cache = File(context.cacheDir, "gnosis").apply { mkdirs() }
        val level = LogLevel.get(context)
        LogBuffer.log("gnosis: starting core (${GnosisNative.version()}), log level ${level.name.lowercase()}")
        GnosisNative.initAndroid(context.applicationContext)
        GnosisNative.setLogFilter(level.coreFilter)
        val blokli = props.getProperty("blokli_url") ?: error("gnosis.properties has no blokli_url")
        val network = props.getProperty("network") ?: error("gnosis.properties has no network")
        // A core stopped moments ago may still be shutting down: retry for a while.
        var waited = 0
        while (true) {
            val err = GnosisNative.startCore(data.path, cache.path, config, blokli, network) ?: break
            if (err != "core already running" || waited >= 20_000) error("startCore: $err")
            Thread.sleep(1_000)
            waited += 1_000
        }
        coreRunning = true
        // The loops live as long as the process (the instance is a per-process singleton) and
        // idle while the core is stopped, so restarts never duplicate them.
        if (loopsStarted.compareAndSet(false, true)) {
            Thread(::eventLoop, "gnosis-events").apply { isDaemon = true }.start()
            Thread(::statusLoop, "gnosis-status").apply { isDaemon = true }.start()
        }
    }

    /** Polls the core's status: locations, funding needs, run-mode and connection changes. */
    private fun statusLoop() {
        var lastRun = ""
        var lastConn = ""
        var lastDests = ""
        while (true) {
            if (!coreRunning) { Thread.sleep(2_000); continue }
            val raw = GnosisNative.command(GnosisProtocol.STATUS)
            val level = LogLevel.get(context)
            if (level != LogLevel.NORMAL) runCatching { File(context.cacheDir, "gnosis-status.json").writeText(raw) }
            val data = GnosisProtocol.statusData(raw)
            if (data != null) {
                status = data
                val runMode = GnosisProtocol.runMode(data) ?: "?"
                if (runMode != lastRun) { LogBuffer.log("gnosis: run mode $runMode"); lastRun = runMode }
                val funding = GnosisProtocol.fundingRequest(data)
                if (funding != null && funding != fundingRequest) {
                    LogBuffer.log("gnosis: FUNDING NEEDED for node ${funding.address}: ${funding.need}")
                }
                fundingRequest = funding
                val connObj = data.optJSONObject("connection")
                val conn = if (level != LogLevel.NORMAL) short(connObj?.toString() ?: "?", 400) else connObj?.optString("type") ?: "?"
                if (conn != lastConn) { LogBuffer.log("gnosis: connection $conn"); lastConn = conn }
                destinations = GnosisProtocol.destinations(data)
                val names = destinations.joinToString(", ") { it.toLocation().label }
                if (names != lastDests && names.isNotEmpty()) { LogBuffer.log("gnosis: ${destinations.size} locations: $names"); lastDests = names }
            }
            Thread.sleep(if (state == Tunnel.State.CONNECTING) 3_000 else 10_000)
        }
    }

    /** Retries Connect until the core accepts it, for the current [target]. */
    private fun connectWhenReady() {
        var waitedFor = ""
        while (wantConnected && state == Tunnel.State.CONNECTING) {
            val wanted = target ?: return
            val runMode = status?.let { GnosisProtocol.runMode(it) }
            val id = find(wanted)?.id
            if (runMode == "running" && id != null) {
                val reply = GnosisNative.command(GnosisProtocol.connect(id))
                val type = GnosisProtocol.statusData(reply)?.optString("type") ?: "?"
                LogBuffer.log("gnosis: connect $id -> $type")
                if (type in GnosisProtocol.CONNECT_ACCEPTED) return
                LogBuffer.log("gnosis: connect reply ${short(reply, 600)}")
            } else {
                val why = if (runMode != "running") "core is ${runMode ?: "starting"}" else "location $wanted not discovered yet"
                if (why != waitedFor) { LogBuffer.log("gnosis: waiting to connect: $why"); waitedFor = why }
            }
            Thread.sleep(3_000)
        }
    }

    /** Core events (tun requests, start/stop) and the core's log lines for the screen. */
    private fun eventLoop() {
        while (true) {
            GnosisNative.pollLogs()?.lineSequence()?.forEach { LogBuffer.logCore(short(it, 400)) }
            val raw = GnosisNative.pollEvent()
            if (raw == null) { Thread.sleep(200); continue }
            val event = org.json.JSONObject(raw)
            if (LogLevel.get(context) != LogLevel.NORMAL) LogBuffer.log("gnosis event: ${short(raw, 300)}")
            when (event.optString("type")) {
                "setup_tunnel" -> setupTunnel(event)
                "teardown" -> {
                    // The tun is kept across the core's reconnects; it is closed on disconnect.
                    if (state == Tunnel.State.CONNECTED) set(Tunnel.State.CONNECTING, "core is reconnecting")
                }
                "core_error" -> { coreRunning = false; set(Tunnel.State.ERROR, event.optString("error")) }
                "core_stopped" -> { coreRunning = false }
            }
        }
    }

    /**
     * Answers the core's tun request. Same parameters as the current tun (an in-place reconnect):
     * hand over the same tun so open connections survive. Otherwise build the new tun first and
     * only then close the old one, so there is no moment without a VPN.
     */
    private fun setupTunnel(event: org.json.JSONObject) {
        val id = event.getLong("request_id")
        val address = event.getString("address")
        val mtu = event.getInt("mtu")
        val dns = event.optString("dns")
        val params = "$address|$mtu|$dns"
        try {
            val current = tun
            if (current != null && params == tunParams) {
                GnosisNative.tunReady(id, current.fd)?.let { error(it) }
                set(Tunnel.State.CONNECTED, "reconnected in place, tun kept")
                return
            }
            val service = GnosisVpnService.obtain(context)
            val pfd = service.establish(address, mtu, dns.ifEmpty { null })
                ?: error("establish() returned null (VPN permission missing?)")
            tun = pfd
            tunParams = params
            current?.let { runCatching { it.close() } }
            GnosisNative.tunReady(id, pfd.fd)?.let { error(it) }
            set(Tunnel.State.CONNECTED, "tun $address mtu $mtu")
        } catch (e: Exception) {
            LogBuffer.log("gnosis: tun setup failed: ${e.javaClass.simpleName}: ${e.message}")
            GnosisNative.tunReady(id, -1)
            set(Tunnel.State.ERROR, "tun setup failed")
        }
    }

    private fun closeTun(stopService: Boolean) {
        tun?.let { runCatching { it.close() } }
        tun = null
        tunParams = null
        if (stopService) GnosisVpnService.stop(context)
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
