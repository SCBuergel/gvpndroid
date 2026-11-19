package org.gnosisvpn.stub

import java.io.Closeable

/**
 * The tun GnosisTunnel owns, with the parameters it was built for. Handing it to the core,
 * replacing it and closing it all take one lock, and a tun request is refused once the VPN is no
 * longer [wanted] (a disconnect, give-up or shutdown began): a request answered after the close
 * can neither keep a tun open nor mark the tunnel connected.
 */
internal class TunSlot<T : Closeable>(private val wanted: () -> Boolean) {
    enum class Answer { HANDED, REFUSED, BUILD_NEW }

    private var tun: T? = null
    private var params: String? = null

    /** An in-place reconnect: when the current tun has [params], [hand] gets it under the lock. */
    @Synchronized fun handSame(params: String, hand: (T) -> Unit): Answer {
        if (!wanted()) return Answer.REFUSED
        val t = tun?.takeIf { this.params == params } ?: return Answer.BUILD_NEW
        hand(t)
        return Answer.HANDED
    }

    /**
     * Makes [fresh] (built outside the lock) the tun for [params], closes the previous one and
     * hands [fresh] over; or closes [fresh] and returns false when the VPN stopped being wanted.
     */
    @Synchronized fun install(fresh: T, params: String, hand: (T) -> Unit): Boolean {
        if (!wanted()) { runCatching { fresh.close() }; return false }
        val old = tun
        tun = fresh
        this.params = params
        old?.let { runCatching { it.close() } }
        hand(fresh)
        return true
    }

    @Synchronized fun close() {
        tun?.let { runCatching { it.close() } }
        tun = null
        params = null
    }
}
