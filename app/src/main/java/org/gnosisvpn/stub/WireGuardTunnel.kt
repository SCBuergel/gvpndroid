package org.gnosisvpn.stub

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import com.wireguard.android.backend.Tunnel as WgTunnel

/**
 * Plain WireGuard backend using the official prebuilt wireguard-go library.
 * Will be replaced by a Gnosis VPN backend (WireGuard over a HOPR session).
 * Requires VpnService.prepare() to have been granted before connect().
 */
class WireGuardTunnel(context: Context, private val store: ConfigStore) : Tunnel {
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Tunnel.Listener>()

    @Volatile
    override var state: Tunnel.State = Tunnel.State.DISCONNECTED
        private set

    private val wg = object : WgTunnel {
        override fun getName() = "gvpn"
        override fun onStateChange(newState: WgTunnel.State) {
            // Called by the backend, e.g. when the system revokes the VPN or another VPN takes over.
            if (newState == WgTunnel.State.DOWN && state == Tunnel.State.CONNECTED) {
                set(Tunnel.State.DISCONNECTED, "tunnel went down outside the app (revoked or replaced?)")
            }
        }
    }

    override fun connect() {
        if (state == Tunnel.State.CONNECTING || state == Tunnel.State.CONNECTED) return
        set(Tunnel.State.CONNECTING, null)
        worker.execute {
            try {
                val config = store.load() ?: throw IllegalStateException("no config imported")
                backend.setState(wg, WgTunnel.State.UP, config)
                set(Tunnel.State.CONNECTED, "wireguard-go ${runCatching { backend.version }.getOrDefault("?")}")
                logKillSwitch()
            } catch (e: Exception) {
                set(Tunnel.State.ERROR, describe(e))
            }
        }
    }

    override fun disconnect() {
        if (state == Tunnel.State.DISCONNECTED || state == Tunnel.State.DISCONNECTING) return
        set(Tunnel.State.DISCONNECTING, null)
        worker.execute {
            try {
                backend.setState(wg, WgTunnel.State.DOWN, null)
                set(Tunnel.State.DISCONNECTED, null)
            } catch (e: Exception) {
                set(Tunnel.State.ERROR, describe(e))
            }
        }
    }

    override fun stats(): Tunnel.Stats? {
        if (state != Tunnel.State.CONNECTED) return null
        return try {
            val s = backend.getStatistics(wg)
            val handshake = s.peers().mapNotNull { s.peer(it)?.latestHandshakeEpochMillis() }
                .filter { it > 0 }.maxOrNull()
            Tunnel.Stats(s.totalRx(), s.totalTx(), handshake)
        } catch (e: Exception) {
            null
        }
    }

    override fun addListener(listener: Tunnel.Listener) { listeners += listener }
    override fun removeListener(listener: Tunnel.Listener) { listeners -= listener }

    /** Always-on VPN + "Block connections without VPN" is Android's kill switch; the user sets it in system settings. */
    private fun logKillSwitch() {
        val alwaysOn = runCatching { backend.isAlwaysOn }.getOrNull()
        val lockdown = runCatching { backend.isLockdownEnabled }.getOrNull()
        LogBuffer.log("system kill switch: always-on=$alwaysOn, block-without-VPN=$lockdown")
    }

    private fun set(s: Tunnel.State, detail: String?) {
        state = s
        main.post { listeners.forEach { it.onStateChanged(s, detail) } }
    }

    private fun describe(e: Exception): String =
        if (e is BackendException) "backend error ${e.reason}" + (e.format?.takeIf { it.isNotEmpty() }?.let { " ${it.toList()}" } ?: "")
        else "${e.javaClass.simpleName}: ${e.message}"
}
