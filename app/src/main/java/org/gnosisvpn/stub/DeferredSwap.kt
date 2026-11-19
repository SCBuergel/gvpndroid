package org.gnosisvpn.stub

/**
 * A backend swap that waits for the tunnel to be down ([App.canSwapBackend]): the swap disables
 * the old backend's VpnService and repoints App.tunnel, so it must never run under a live tunnel.
 * Only used on the main thread (requests and tunnel state events).
 */
internal class DeferredSwap(private val swap: () -> Unit) {
    var pending = false
        private set

    /** Swaps now if [state] is down and returns true; otherwise swaps at the first down [onState]. */
    fun request(state: Tunnel.State): Boolean {
        pending = !App.swapWhenDown(state, swap)
        return !pending
    }

    /** A tunnel state change: runs a pending swap once the tunnel is down; returns whether it ran. */
    fun onState(state: Tunnel.State): Boolean {
        if (!pending || !App.canSwapBackend(state)) return false
        pending = false
        swap()
        return true
    }
}
