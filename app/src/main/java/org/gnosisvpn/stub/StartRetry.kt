package org.gnosisvpn.stub

/** A core start that failed only because there is no usable network yet (the Blokli host does not resolve). */
internal class NoNetwork(message: String) : Exception(message)

/** startCore's error [err] as an exception: [NoNetwork] for the bridge's resolve error, which is transient. */
internal fun startCoreError(err: String): Exception =
    if (err.startsWith("resolve ")) NoNetwork("startCore: $err") else IllegalStateException("startCore: $err")

/**
 * Retries a core start that failed with [NoNetwork] (at boot, Always-on usually starts before
 * Wi-Fi or mobile data is up) while [wanted], waiting [firstMs] doubling to [maxMs] between tries.
 * [wake] cuts a wait short; call it after [wanted] turned false.
 */
internal class StartRetry(private val wanted: () -> Boolean, private val firstMs: Long = 2_000, private val maxMs: Long = 15_000) {
    private val lock = Object()

    fun wake() = synchronized(lock) { lock.notifyAll() }

    /** Runs [start]; true once it returned, false when no longer [wanted]. Other exceptions pass through. */
    fun run(waiting: (NoNetwork) -> Unit, start: () -> Unit): Boolean {
        var delay = firstMs
        while (wanted()) {
            try {
                start()
                return true
            } catch (e: NoNetwork) {
                if (!wanted()) return false
                waiting(e)
                synchronized(lock) { if (wanted()) lock.wait(delay) }
                delay = minOf(delay * 2, maxMs)
            }
        }
        return false
    }
}
