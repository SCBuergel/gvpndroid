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
    /** Generation of the core this instance started; older cores' lifecycle events are ignored. */
    @Volatile private var coreGeneration = -1L
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

    override val phase: String? get() = status?.let { GnosisProtocol.runMode(it) }

    @Volatile override var startup: Tunnel.Startup? = null; private set
    @Volatile override var failure: String? = null; private set
    /** Why the last connect failed (the core refused it or gave up), in words; cleared on connect. */
    @Volatile override var lastError: String? = null; private set
    /** True while connectWhenReady is issuing Connect: a "disconnected" then is not the core giving up. */
    @Volatile private var connectLoop = false
    @Volatile override var session: Tunnel.Session? = null; private set
    @Volatile override var chunkBytes: Long? = null; private set
    @Volatile override var wallet: Tunnel.Wallet? = null; private set
    @Volatile private var counters: Tunnel.Stats? = null
    @Volatile private var balanceDue = 0L
    /** The configured data price (pix_strategy.price_per_byte), in whole wxHOPR per byte. */
    private val pricePerByte: java.math.BigDecimal? by lazy {
        runCatching { GnosisProtocol.pricePerByte(File(dir, "gnosis.toml").readText()) }.getOrNull()
    }

    override fun refreshExits() {
        if (coreRunning && phase == "running") Thread { GnosisNative.command(GnosisProtocol.PROBING_BOOST) }.start()
    }

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
        lastError = null
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
            // Marked as a connect in progress first, so the core's "disconnected" while it switches
            // is not taken for the core giving up.
            connectLoop = true
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
            destinations = emptyList(); session = null; wallet = null; startup = null; failure = null; counters = null
            set(Tunnel.State.DISCONNECTED, null)
        }
    }

    override fun stats(): Tunnel.Stats? = counters

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
            // Set before starting, so even an immediate core_error of the new core is matched.
            // Only this (synchronized) method starts cores, and a refused start uses no number.
            coreGeneration = GnosisNative.coreGeneration() + 1
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
        var lastConnType = ""
        var lastTrouble = false
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
                // The core gave up on an attempt (back to disconnected while the user still wants
                // the VPN and no Connect is being sent): say why instead of waiting forever.
                val connType = connObj?.optString("type") ?: ""
                // Only when the core also dropped its target: while it switches exits, opens a probe or
                // waits for a route it reports "disconnected" but keeps the target.
                val coreTarget = data.takeIf { it.has("target") && !it.isNull("target") }?.optString("target")
                if (GnosisProtocol.coreGaveUp(wantConnected, connectLoop, lastConnType, connType, coreTarget))
                    worker.execute { giveUp(target?.let { find(it) }?.health?.why ?: "The exit did not answer the connection attempt.") }
                lastConnType = connType
                destinations = GnosisProtocol.destinations(data)
                startup = GnosisProtocol.startup(data)
                failure = GnosisProtocol.failure(data)
                chunkBytes = GnosisProtocol.chunkBytes(data)
                // the session names the exit by the core's id; the UI keys exits by address
                session = GnosisProtocol.session(data)?.let { s -> s.copy(exitId = find(s.exitId)?.address ?: s.exitId) }
                // Diagnostics: when a stall or a reconnect starts, record what the core saw (the
                // connection with its stall and PIX state, and nerd_stats: WireGuard counters
                // history, SURBs). It goes to the log (and, in debug builds, the log relay).
                val trouble = connObj?.let { it.has("stall") && !it.isNull("stall") } == true || connType == "reconnecting"
                if (trouble && !lastTrouble) {
                    LogBuffer.log("gnosis: diagnostics connection=${short(connObj?.toString() ?: "?", 1500)}")
                    LogBuffer.log("gnosis: diagnostics nerd_stats=${short(GnosisNative.command(GnosisProtocol.NERD_STATS), 3000)}")
                }
                lastTrouble = trouble
                val now = System.currentTimeMillis()
                if (runMode == "running" && now >= balanceDue) {
                    GnosisProtocol.wallet(GnosisNative.command(GnosisProtocol.BALANCE))?.let { w ->
                        wallet = w.copy(dataBytes = pricePerByte?.let { p -> w.traffic.divide(p, java.math.MathContext.DECIMAL64).toLong() })
                    }
                    balanceDue = now + 20_000
                }
                counters = if (session != null && runMode == "running") GnosisProtocol.rxTx(GnosisNative.command(GnosisProtocol.NERD_STATS))
                    ?.let { (rx, tx) -> Tunnel.Stats(rx, tx, null) } else null
                val names = destinations.joinToString(", ") { it.toLocation().label }
                if (names != lastDests && names.isNotEmpty()) { LogBuffer.log("gnosis: ${destinations.size} locations: $names"); lastDests = names }
            }
            Thread.sleep(if (session != null || state != Tunnel.State.DISCONNECTED) 2_000 else 4_000)
        }
    }

    /** Retries Connect until the core accepts it, for the current [target]. */
    private fun connectWhenReady() {
        connectLoop = true
        try { connectLoopBody() } finally { connectLoop = false }
    }

    private fun connectLoopBody() {
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
                // A refusal (unable to connect, or an error) is said at once instead of retried
                // forever; the user picks another exit or tries again (as gvpndows does).
                val why = GnosisProtocol.connectRefusal(reply)
                if (why != null) {
                    giveUp(find(wanted)?.health?.why?.takeIf { type == "unable_to_connect" } ?: why)
                    return
                }
            } else {
                val why = if (runMode != "running") "core is ${runMode ?: "starting"}" else "location $wanted not discovered yet"
                if (why != waitedFor) { LogBuffer.log("gnosis: waiting to connect: $why"); waitedFor = why }
            }
            Thread.sleep(3_000)
        }
    }

    /** Ends a connect attempt the core refused or gave up on: idle, with [why] for the UI. */
    private fun giveUp(why: String) {
        LogBuffer.log("gnosis: connect ended without a connection: $why")
        lastError = why
        wantConnected = false
        closeTun(stopService = true)
        set(Tunnel.State.ERROR, why)
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
                // A late event of an earlier core (after a quick stop and restart) must not
                // mark the current one as stopped.
                "core_error" -> if (event.optLong("generation", -2) == coreGeneration) {
                    coreRunning = false
                    set(Tunnel.State.ERROR, event.optString("error"))
                }
                "core_stopped" -> if (event.optLong("generation", -2) == coreGeneration) coreRunning = false
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
