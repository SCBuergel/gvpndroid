package org.gnosisvpn.stub

import android.content.Context
import com.wireguard.config.Config
import java.io.File

/**
 * Holds the imported WireGuard config in app-private storage on the phone.
 * It is never part of the APK or the repository.
 */
class ConfigStore(context: Context) {
    private val file = File(context.filesDir, "tunnel.conf")

    /** Parses and validates [text]; stores it only if valid. Returns the parsed config. */
    fun import(text: String): Config {
        val config = Config.parse(text.byteInputStream())
        file.writeText(text)
        return config
    }

    fun load(): Config? = if (file.exists()) file.inputStream().use { Config.parse(it) } else null

    fun clear() { file.delete() }

    /** One-line summary without any key material. */
    fun describe(config: Config): String {
        val iface = config.`interface`
        val addrs = iface.addresses.joinToString(",")
        val dns = iface.dnsServers.joinToString(",") { it.hostAddress ?: "?" }
        val peers = config.peers.joinToString("; ") { p ->
            val ep = p.endpoint.map { it.toString() }.orElse("no endpoint")
            "$ep allowed=${p.allowedIps.joinToString(",")}"
        }
        return "address=$addrs dns=$dns peer=$peers"
    }
}
