package org.gnosisvpn.stub

/**
 * The Gnosis VPN client core (Rust, `libgnosisvpn.so`, built from rust/gnosis_vpn-android).
 * Optional for now: builds without the library still work with the WireGuard backend.
 */
object GnosisNative {
    /** null when the library loaded, otherwise why not. */
    val loadError: String? = try {
        System.loadLibrary("gnosisvpn")
        null
    } catch (e: Throwable) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    @JvmStatic external fun version(): String

    /** null if the client accepts [toml], otherwise the error text. */
    @JvmStatic external fun checkConfig(toml: String): String?

    /** Starts the client core in the background; null if started, otherwise the error text. */
    @JvmStatic external fun startCore(
        dataDir: String,
        cacheDir: String,
        configToml: String,
        blokliUrl: String,
        network: String,
    ): String?
}
