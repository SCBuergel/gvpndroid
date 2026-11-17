package org.gnosisvpn.stub

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashRecorder()
        store = ConfigStore(this)
        selectBackend()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(TunnelService.CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW)
        )

        LogBuffer.log("process started, backend: ${if (usesGnosis) "gnosis" else "wireguard"}")
        GnosisNative.loadError?.let { LogBuffer.log("gnosis core not available: $it") }
            ?: LogBuffer.log("gnosis core: ${GnosisNative.version()}, config check: ${GnosisNative.checkConfig("") ?: "ok"}")
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

    /** One per process: the Rust core and its event queue exist once. */
    private val gnosisTunnel by lazy { GnosisTunnel(this) }

    /** Picks the backend from the device settings; called at start and after switching. */
    fun selectBackend() {
        if (this::tunnel.isInitialized) tunnel.removeListener(stateLogger)
        usesGnosis = GnosisTunnel.isConfigured(this)
        tunnel = if (usesGnosis) gnosisTunnel else WireGuardTunnel(this, store, onSystemStart = {
            // Android started the VPN by itself: "Always-on VPN" is enabled for this app.
            LogBuffer.log("always-on VPN triggered by the system")
            TunnelService.start(this, TunnelService.ACTION_CONNECT)
        })
        tunnel.addListener(stateLogger)
        // Start the Gnosis core right away: syncing, peering and route probing take a while.
        (tunnel as? GnosisTunnel)?.prestart()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
