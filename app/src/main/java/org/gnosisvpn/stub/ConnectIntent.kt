package org.gnosisvpn.stub

/**
 * Whether the user wants GnosisTunnel connected, ordered against disconnects still running on
 * the worker. A connect asked while one runs only becomes [wanted] when the last of them has
 * finished, so until then the old connection's tun requests are refused and the core is not
 * judged to have given up; then the queued connect task connects (last action wins).
 */
internal class ConnectIntent {
    @Volatile private var want = false
    private var pendingConnect = false
    private var disconnects = 0

    /** Read by tun requests, the connect loop and the status loop; false also drops a pending connect. */
    var wanted: Boolean
        get() = want
        set(v) = synchronized(this) { want = v; if (!v) pendingConnect = false }

    @Synchronized fun connect() {
        if (disconnects > 0) pendingConnect = true else want = true
    }

    /** A disconnect task was queued. */
    @Synchronized fun disconnect() {
        want = false; pendingConnect = false; disconnects++
    }

    /** A disconnect task finished: true when the tunnel is now idle, false when a connect takes over. */
    @Synchronized fun disconnectDone(): Boolean {
        disconnects--
        if (!pendingConnect) return true
        if (disconnects == 0) { pendingConnect = false; want = true }
        return false
    }
}
