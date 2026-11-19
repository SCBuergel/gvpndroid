package org.gnosisvpn.stub

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * Turns the Gnosis backend on and off on a device without adb: copies the data files bundled
 * as assets (scripts/gnosis-assets.sh) into app-private storage, and exports the node identity.
 * Switching back to WireGuard only removes gnosis.properties: the identity (which controls the
 * node's funds) is never deleted.
 */
object GnosisSetup {
    private const val ASSETS = "gnosis"

    fun bundled(context: Context) = runCatching { context.assets.list(ASSETS)?.contains("config.toml") == true }.getOrDefault(false)

    /** Copies data files and writes gnosis.toml / gnosis.properties. Slow (46 MB): not on the main thread. */
    fun enable(context: Context) {
        val nb = context.noBackupFilesDir
        val share = File(nb, "gnosis-share").apply { mkdirs() }
        copyAsset(context, "$ASSETS/geoip.mmdb", File(share, "geoip.mmdb"))
        val zk = File(share, "curvy-zk").apply { mkdirs() }
        context.assets.list("$ASSETS/curvy-zk")!!.forEach { copyAsset(context, "$ASSETS/curvy-zk/$it", File(zk, it)) }
        val template = context.assets.open("$ASSETS/config.toml").bufferedReader().readText()
        val config = assembleConfig(template, File(share, "geoip.mmdb").path, zk.path)
        GnosisNative.checkConfig(config)?.let { error("bundled config rejected: $it") }
        install(nb, config) { context.assets.open("$ASSETS/gnosis.properties") }
    }

    /** Writes gnosis.toml and, if there is none, gnosis.properties from [defaultProps]; clears a WireGuard choice. */
    fun install(noBackup: File, config: String, defaultProps: () -> java.io.InputStream) {
        File(noBackup, "gnosis.toml").writeText(config)
        val props = File(noBackup, "gnosis.properties")
        if (!props.exists()) defaultProps().use { input -> props.outputStream().use { input.copyTo(it) } }
        File(noBackup, WIREGUARD_CHOSEN).delete()
    }

    /**
     * The packaged v7 config with the two data-file paths set. Both are top-level keys, so they
     * must come before the first [table]; the template's own version line is replaced.
     */
    fun assembleConfig(template: String, geoipDb: String, curvyZkDir: String): String = buildString {
        append("version = 7\n")
        append("geoip_db = \"$geoipDb\"\n")
        append("curvy_zk_dir = \"$curvyZkDir\"\n")
        template.lineSequence().filterNot { it.trim().startsWith("version") }.forEach { append(it).append('\n') }
    }

    /** Switches to WireGuard and records that choice, so a new process does not set up Gnosis again. */
    fun disable(noBackup: File) {
        File(noBackup, WIREGUARD_CHOSEN).writeText("")
        File(noBackup, "gnosis.properties").delete()
    }

    fun choseWireGuard(noBackup: File) = File(noBackup, WIREGUARD_CHOSEN).exists()

    private const val WIREGUARD_CHOSEN = "backend-wireguard"

    /** The identity files of the configured network, if the core has created them. */
    private fun identityDir(context: Context): File? {
        val props = File(context.noBackupFilesDir, "gnosis.properties").takeIf { it.exists() } ?: return null
        val network = Properties().apply { props.inputStream().use { load(it) } }.getProperty("network") ?: return null
        return File(context.noBackupFilesDir, "gnosis/networks/$network").takeIf { File(it, "hopr.id").exists() }
    }

    fun hasIdentity(context: Context) = identityDir(context) != null

    /** Writes the identity (keystore + its password) as JSON to a user-chosen document. */
    fun exportIdentity(context: Context, target: Uri) {
        val dir = identityDir(context) ?: error("no identity yet: connect once so the core creates it")
        val json = JSONObject()
            .put("network", dir.name)
            .put("hopr.id", File(dir, "hopr.id").readText())
            .put("hopr.pass", File(dir, "hopr.pass").readText())
            .put("note", "Gnosis VPN node identity. Whoever has this file controls the node's funds.")
        context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(json.toString(2).toByteArray()) }
    }

    private fun copyAsset(context: Context, asset: String, to: File) {
        context.assets.open(asset).use { input -> to.outputStream().use { input.copyTo(it) } }
    }
}
