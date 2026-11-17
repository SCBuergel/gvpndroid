package org.gnosisvpn.stub

/**
 * The Gnosis VPN client core (Rust, `libgnosisvpn.so`, built from rust/gnosis_vpn-android).
 * Optional: builds without the library still work with the WireGuard backend.
 * See rust/gnosis_vpn-android/src/lib.rs for the protocol.
 */
object GnosisNative {
    /** null when the library loaded, otherwise why not. */
    val loadError: String? = try {
        System.loadLibrary("gnosisvpn")
        null
    } catch (e: Throwable) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    /** Once per process before startCore: hands the TLS certificate verifier the JVM and a Context. */
    @JvmStatic external fun initAndroid(context: android.content.Context)

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

    /** Sends a WorkerCommand as JSON and returns the core's Response as JSON. Blocks; not on the main thread. */
    @JvmStatic external fun command(json: String): String

    /** The next core event as JSON (setup_tunnel, teardown, core_started, core_error, ...), or null. */
    @JvmStatic external fun pollEvent(): String?

    /** Answers a setup_tunnel event with the tun fd (or -1 on failure); null on success, else the error. */
    @JvmStatic external fun tunReady(requestId: Long, fd: Int): String?

    @JvmStatic external fun stopCore()

    /** Changes the core's log filter (EnvFilter syntax) at runtime; null on success. */
    @JvmStatic external fun setLogFilter(filter: String): String?

    /** Core log lines buffered since the last call (newline-separated), or null. */
    @JvmStatic external fun pollLogs(): String?
}
