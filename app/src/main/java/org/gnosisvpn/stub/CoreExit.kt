package org.gnosisvpn.stub

/**
 * What GnosisTunnel does when its current core exits by itself (core_stopped or core_error not
 * caused by our stopCore). Upstream's core returns after a final connection error or when Blokli
 * moved, expecting its root process to start a new one; on Android nothing else does.
 */
internal enum class CoreExit {
    /** No connection is wanted or under way: stay idle, the next connect starts a core. */
    IDLE,
    /** Start a new core and connect it to the same target, keeping the tun (traffic stays blocked). */
    RESTART,
    /** Restarted too often without reaching a connection: end the attempt with a reason. */
    GIVE_UP;

    companion object {
        /** Restarts in a row without a connection before giving up. */
        const val MAX_RESTARTS = 3

        /** [connecting]: the user wants the VPN and the tunnel is connecting or connected. */
        fun decide(connecting: Boolean, restartsSinceConnected: Int): CoreExit = when {
            !connecting -> IDLE
            restartsSinceConnected < MAX_RESTARTS -> RESTART
            else -> GIVE_UP
        }
    }
}
