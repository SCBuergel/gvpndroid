package org.gnosisvpn.stub

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import java.util.concurrent.Executors
import com.wireguard.android.backend.Tunnel as WgTunnel

/**
 * Plain WireGuard backend using the official prebuilt wireguard-go library.
 * Will be replaced by a Gnosis VPN backend (WireGuard over a HOPR session).
 * Requires VpnService.prepare() to have been granted before connect().
 *
 * [onSystemStart] is called when Android starts the VpnService on its own (always-on VPN);
 * the app should then bring the tunnel up through its usual path.
 */
class WireGuardTunnel(
    context: Context,
    private val store: ConfigStore,
    onSystemStart: () -> Unit,
) : Tunnel {
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val main = Handler(Looper.getMainLooper())
    private val seq: TunnelSequence = TunnelSequence(
        Executors.newSingleThreadExecutor(), { main.post(it) },
        up = {
            val config = store.load() ?: throw IllegalStateException("no config imported")
            backend.setState(wg, WgTunnel.State.UP, config)
            logKillSwitch()
            "wireguard-go ${runCatching { backend.version }.getOrDefault("?")}"
        },
        down = { backend.setState(wg, WgTunnel.State.DOWN, null) },
        describe = ::describe,
    )

    init {
        GoBackend.setAlwaysOnCallback { onSystemStart() }
    }

    override val state: Tunnel.State get() = seq.state

    private val wg: WgTunnel = object : WgTunnel {
        override fun getName() = "gvpn"
        override fun onStateChange(newState: WgTunnel.State) {
            // Called by the backend, e.g. when the system revokes the VPN or another VPN takes over.
            if (newState == WgTunnel.State.DOWN) seq.wentDown("tunnel went down outside the app (revoked or replaced?)")
        }
    }

    /** A connect during a disconnect follows it (TunnelSequence): no DISCONNECTED in between. */
    override fun connect() = seq.connect()

    override fun disconnect() = seq.disconnect()

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

    override fun addListener(listener: Tunnel.Listener) { seq.listeners += listener }
    override fun removeListener(listener: Tunnel.Listener) { seq.listeners -= listener }

    /** Always-on VPN + "Block connections without VPN" is Android's kill switch; the user sets it in system settings. */
    private fun logKillSwitch() {
        val alwaysOn = runCatching { backend.isAlwaysOn }.getOrNull()
        val lockdown = runCatching { backend.isLockdownEnabled }.getOrNull()
        LogBuffer.log("system kill switch: always-on=$alwaysOn, block-without-VPN=$lockdown")
    }

    private fun describe(e: Exception): String =
        if (e is BackendException) "backend error ${e.reason}" + (e.format?.takeIf { it.isNotEmpty() }?.let { " ${it.toList()}" } ?: "")
        else "${e.javaClass.simpleName}: ${e.message}"
}
