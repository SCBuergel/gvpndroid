package org.gnosisvpn.stub

import android.app.Application
import org.gnosisvpn.stub.ui.ongoingNote
import org.gnosisvpn.stub.ui.screenCode
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class App : Application() {
    lateinit var store: ConfigStore
        private set
    lateinit var tunnel: Tunnel
        private set
    /** Gnosis VPN backend (when gnosis.properties exists) instead of plain WireGuard. */
    var usesGnosis = false
        private set

    /**
     * The UI's state machine over the real Gnosis VPN core ([tunnel]). Created on first use; when
     * this install has no Gnosis backend yet, it is set up in the background (data files from the
     * APK), so a fresh install starts on Gnosis VPN without the developer screen.
     */
    val engine: org.gnosisvpn.stub.ui.Engine by lazy {
        org.gnosisvpn.stub.ui.GnosisEngine({ tunnel }, getSharedPreferences("ui", MODE_PRIVATE)).also { e ->
            e.hooks = org.gnosisvpn.stub.ui.AppHooks(this)
            var lastConn: org.gnosisvpn.stub.ui.Conn? = null
            var lastCode = ""
            var lastNote = ""
            e.onChange = {
                val n = e.ongoingNote()
                val key = n?.let { it.title + it.body + it.acts } ?: ""
                if (key != lastNote) { lastNote = key; TunnelService.refresh(this) }
                if (e.conn != lastConn) { lastConn = e.conn; org.gnosisvpn.stub.ui.GnosisTileService.update(this) }
                val code = e.screenCode().first
                if (code != lastCode) { lastCode = code; android.util.Log.i("GnosisUi", "screen $code ${e.screenCode().second}") }
            }
            e.start()
            ensureGnosis()
        }
    }

    /**
     * Sets up the Gnosis backend on a fresh install (off the main thread), not when the user chose
     * WireGuard on the developer screen. Swaps backends only while the tunnel is down; otherwise
     * the next process starts on Gnosis.
     */
    fun ensureGnosis() {
        if (!shouldSetUpGnosis(usesGnosis, canUseGnosis(), GnosisSetup.choseWireGuard(noBackupFilesDir))) return
        Thread {
            runCatching { switchBackend(true) }
                .onSuccess {
                    android.os.Handler(mainLooper).post {
                        if (swapWhenDown(tunnel.state, ::selectBackend)) LogBuffer.log("gnosis backend set up for the app")
                        else LogBuffer.log("gnosis backend set up; used from the next start (tunnel is ${tunnel.state})")
                    }
                }
                .onFailure { LogBuffer.log("gnosis setup failed: ${it.javaClass.simpleName}: ${it.message}") }
        }.start()
    }

    /** Restarts the Gnosis core (after a failure); a pending switch from WireGuard waits for the tunnel to be down. */
    fun restartGnosis() {
        if (usesGnosis) selectBackend()
        else if (!swapWhenDown(tunnel.state, ::selectBackend)) LogBuffer.log("restart: not while the WireGuard tunnel is ${tunnel.state}")
    }

    val ui: org.gnosisvpn.stub.ui.UiState by lazy {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        org.gnosisvpn.stub.ui.UiState(prefs.getString("mode", "system") ?: "system") { prefs.edit().putString("mode", it).apply() }.also {
            // DEBUG ONLY. REMOVE BEFORE PRODUCTION (developer log relay).
            it.logRelayOn = DebugLogRelay.available && DebugLogRelay.isOn(this)
        }
    }
    /** The visible UI activity, for hooks that need one (file picker, share sheet). */
    var activity: org.gnosisvpn.stub.ui.UiActivity? = null

    /** Lockdown (Always-on + Block connections without VPN) as Android reports it to the running VpnService; null if unknown. */
    fun readLockdown(): Boolean? = if (android.os.Build.VERSION.SDK_INT >= 29) GnosisVpnService.current()?.isLockdownEnabled else null

    override fun onCreate() {
        super.onCreate()
        instance = this
        org.gnosisvpn.stub.ui.Notifier.channels(this)
        installCrashRecorder()
        // DEBUG ONLY. REMOVE BEFORE PRODUCTION: encrypted log to the developer (every 30 s at first), on by
        // default and switchable. It is real only in debug builds; release builds get a no-op stub.
        DebugLogRelay.start(this)
        store = ConfigStore(this)
        selectBackend()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TunnelService.CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW)
        )

        LogBuffer.log("process started, backend: ${if (usesGnosis) "gnosis" else "wireguard"}")
        GnosisNative.loadError?.let { LogBuffer.log("gnosis core not available: $it") }
            ?: LogBuffer.log("gnosis core: ${GnosisNative.version()}, config check: " +
                (java.io.File(noBackupFilesDir, "gnosis.toml").takeIf { it.isFile }?.let { GnosisNative.checkConfig(it.readText()) ?: "ok" } ?: "no config yet"))
        reportPreviousCrash()
        try {
            watchNetwork()
        } catch (e: Exception) {
            LogBuffer.log("network watcher unavailable: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Without adb there is no logcat, so keep the last crash in a file and show it on the next start. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile().writeText("thread ${thread.name}\n${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun reportPreviousCrash() {
        val f = crashFile()
        if (!f.exists()) return
        val lines = f.readLines()
        LogBuffer.log("PREVIOUS CRASH (${java.util.Date(f.lastModified())}):")
        lines.filter { it.isNotBlank() }.take(25).forEach { LogBuffer.log("  $it") }
        f.delete()
    }

    private fun crashFile() = java.io.File(filesDir, "last-crash.txt")

    /** Logs underlying network changes (Wi-Fi <-> mobile), which is where roaming problems show up. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var last = ""
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = buildList {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                }.joinToString("+").ifEmpty { "other" }
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val desc = "$kind${if (validated) "" else " (not validated)"}"
                if (desc != last) { last = desc; LogBuffer.log("default network: $desc") }
            }
            override fun onLost(network: Network) { last = ""; LogBuffer.log("default network lost") }
        })
    }

    private val stateLogger = Tunnel.Listener { s, detail -> LogBuffer.log("state -> $s" + (detail?.let { " ($it)" } ?: "")) }

    /** Whether this APK can run the Gnosis backend: bundled data files and the Rust core library. */
    fun canUseGnosis() = GnosisSetup.bundled(this) && GnosisNative.loadError == null

    /**
     * Switches between the WireGuard and Gnosis backends. Copies 46 MB of data files when turning
     * Gnosis on: call off the main thread. Throws if the switch cannot be done; the caller then
     * still has the old backend. Turning Gnosis off keeps its node identity.
     */
    fun switchBackend(toGnosis: Boolean) {
        if (toGnosis) GnosisSetup.enable(this) else GnosisSetup.disable(noBackupFilesDir)
    }

    /** Whether the Gnosis core has created a node identity (which then controls the node's funds). */
    fun hasNodeIdentity() = GnosisSetup.hasIdentity(this)

    /** Writes the node identity (key file + password) to a document the user picked. */
    fun exportNodeIdentity(target: android.net.Uri) = GnosisSetup.exportIdentity(this, target)

    /** One per process: the Rust core and its event queue exist once. */
    private val gnosisTunnel by lazy { GnosisTunnel(this) }

    /** Picks the backend from the device settings; called at start and after switching. */
    fun selectBackend() {
        if (this::tunnel.isInitialized) {
            tunnel.removeListener(stateLogger)
            tunnel.shutdown()
        }
        usesGnosis = GnosisTunnel.isConfigured(this)
        enableVpnService(usesGnosis)
        tunnel = if (usesGnosis) gnosisTunnel else WireGuardTunnel(this, store, onSystemStart = {
            // Android started the VPN by itself: "Always-on VPN" is enabled for this app.
            LogBuffer.log("always-on VPN triggered by the system")
            TunnelService.start(this, TunnelService.ACTION_CONNECT)
        })
        tunnel.addListener(stateLogger)
        tunnel.setLogLevel(LogLevel.get(this))
        // Start the Gnosis core right away: syncing, peering and route probing take a while.
        if (usesGnosis) gnosisTunnel.prestart()
    }

    /**
     * Enables exactly one of the app's two VpnServices (the WireGuard library's and the Gnosis
     * one), so "Always-on VPN" starts the backend in use rather than an undefined one.
     */
    private fun enableVpnService(gnosis: Boolean) {
        fun set(component: ComponentName, enabled: Boolean) = packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        set(ComponentName(this, GnosisVpnService::class.java), gnosis)
        set(ComponentName(this, "com.wireguard.android.backend.GoBackend\$VpnService"), !gnosis)
    }

    companion object {
        lateinit var instance: App
            private set

        /** Backends are swapped only while the tunnel is down: the old one's VpnService is disabled. */
        fun canSwapBackend(state: Tunnel.State) = state == Tunnel.State.DISCONNECTED || state == Tunnel.State.ERROR

        /** Runs [swap] (a backend change) only if [canSwapBackend]; returns whether it ran. */
        fun swapWhenDown(state: Tunnel.State, swap: () -> Unit) = canSwapBackend(state).also { if (it) swap() }

        /** Whether to set Gnosis up by itself: on an install that can run it and never chose WireGuard. */
        fun shouldSetUpGnosis(usesGnosis: Boolean, canUseGnosis: Boolean, choseWireGuard: Boolean) =
            !usesGnosis && canUseGnosis && !choseWireGuard
    }
}
