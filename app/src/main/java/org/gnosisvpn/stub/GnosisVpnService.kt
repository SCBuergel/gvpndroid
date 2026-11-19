package org.gnosisvpn.stub

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The VpnService of the Gnosis backend. It builds the tun when the core asks for it;
 * TunnelService owns the foreground notification and the connect/disconnect lifecycle.
 *
 * Only the active backend's VpnService is enabled (App.selectBackend), so "Always-on VPN" starts
 * this one when the Gnosis backend is selected; it then connects through TunnelService.
 */
class GnosisVpnService : VpnService() {
    override fun onCreate() {
        super.onCreate()
        running.complete(this)
    }

    override fun onDestroy() {
        if (running.getNow(null) === this) running = CompletableFuture()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Also here, not only in onCreate: a start can reach an instance that outlived stop()
        // (the system may still be bound to it), and obtain() waits for exactly this.
        if (running.getNow(null) == null) running.complete(this)
        // The system starts a VpnService with the SERVICE_INTERFACE action for Always-on VPN
        // (also after boot); the app's own starts carry no action.
        if (intent?.action == SERVICE_INTERFACE) {
            LogBuffer.log("always-on VPN triggered by the system")
            TunnelService.start(this, TunnelService.ACTION_CONNECT)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        LogBuffer.log("VPN revoked by the system (another VPN took over?)")
        App.instance.tunnel.disconnect()
        super.onRevoke()
    }

    /** Builds the tun as the core asked. The app itself is excluded so HOPR traffic does not loop. */
    fun establish(address: String, mtu: Int, dns: String?): ParcelFileDescriptor? {
        val (ip, prefix) = address.split("/").let { it[0] to (it.getOrNull(1)?.toInt() ?: 32) }
        val builder = Builder()
            .setSession("Gnosis VPN")
            .addAddress(ip, prefix)
            .addRoute("0.0.0.0", 0)
            .setMtu(mtu)
            .addDisallowedApplication(packageName)
        // The core sends DNS servers as a comma-separated list, e.g. "1.1.1.1,8.8.8.8".
        GnosisProtocol.dnsServers(dns).forEach { builder.addDnsServer(it) }
        return builder.establish()
    }

    companion object {
        @Volatile private var running = CompletableFuture<GnosisVpnService>()

        /** The running instance, if any (no start). */
        fun current(): GnosisVpnService? = running.getNow(null)

        /** Starts the service if needed and returns the running instance. */
        fun obtain(context: Context): GnosisVpnService {
            context.startService(Intent(context, GnosisVpnService::class.java))
            return running.get(10, TimeUnit.SECONDS)
        }

        fun stop(context: Context) {
            // Forget the instance first, so an obtain() racing with the stop waits for a new one.
            running = CompletableFuture()
            context.stopService(Intent(context, GnosisVpnService::class.java))
        }
    }
}
