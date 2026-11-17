package org.gnosisvpn.stub

import android.content.Context
import com.wireguard.config.Config
import java.io.File

/**
 * Holds the imported WireGuard config (including the private key) on the phone.
 * Kept in noBackupFilesDir, which Android excludes from cloud backup and device-to-device
 * transfer; data_extraction_rules.xml excludes everything else too. Never in the APK or repo.
 */
class ConfigStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "tunnel.conf")

    init {
        // 0.2.x kept the config in filesDir; move it out of backup/transfer reach.
        val old = File(context.filesDir, "tunnel.conf")
        if (old.exists() && !file.exists()) old.renameTo(file)
        old.delete()
    }

    /** Parses and validates [text]; stores it only if valid. Returns the parsed config. */
    fun import(text: String): Config {
        val config = Config.parse(text.byteInputStream())
        file.writeText(text)
        return config
    }

    fun load(): Config? = if (file.exists()) file.inputStream().use { Config.parse(it) } else null
}

/** One-line summary for the log, without any key material. */
fun describeConfig(config: Config): String {
    val iface = config.`interface`
    val addrs = iface.addresses.joinToString(",")
    val dns = iface.dnsServers.joinToString(",") { it.hostAddress ?: "?" }
    val peers = config.peers.joinToString("; ") { p ->
        val ep = p.endpoint.map { it.toString() }.orElse("no endpoint")
        "$ep allowed=${p.allowedIps.joinToString(",")}"
    }
    return "address=$addrs dns=$dns peer=$peers"
}
