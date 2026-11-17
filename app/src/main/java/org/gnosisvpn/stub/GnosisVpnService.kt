package org.gnosisvpn.stub

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The VpnService for the Gnosis backend. It only builds the tun when the core asks for it;
 * TunnelService owns the foreground notification and the lifecycle.
 */
class GnosisVpnService : VpnService() {
    override fun onCreate() {
        super.onCreate()
        running.complete(this)
    }

    override fun onDestroy() {
        running = CompletableFuture()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

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
        val servers = dns?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().ifEmpty { listOf(DEFAULT_DNS) }
        servers.forEach { builder.addDnsServer(it) }
        return builder.establish()
    }

    companion object {
        private const val DEFAULT_DNS = "9.9.9.9"
        @Volatile private var running = CompletableFuture<GnosisVpnService>()

        /** Starts the service if needed and returns the running instance. */
        fun obtain(context: android.content.Context): GnosisVpnService {
            context.startService(Intent(context, GnosisVpnService::class.java))
            return running.get(10, TimeUnit.SECONDS)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, GnosisVpnService::class.java))
        }
    }
}
