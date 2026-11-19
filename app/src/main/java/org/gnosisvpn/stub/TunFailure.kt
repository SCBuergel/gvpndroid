package org.gnosisvpn.stub

/** Android refused to build the VPN (establish() returned null): the app lacks the VPN permission. */
internal class NoVpnPermission : Exception("establish() returned null (VPN permission missing?)")

/**
 * What a failed tun request means for the connect attempt. The core takes a failed tun as a
 * failed attempt and retries it in place, then exits after its last retry; the attempt must stay
 * alive for that (CONNECTING, so the service stays and the exit is restarted within its bound),
 * unless retrying cannot help.
 */
internal enum class TunFailure {
    /** No VPN permission: every retry would fail the same way, so the attempt ends. */
    NO_PERMISSION,
    /** The service did not start in time, establish() threw, the core did not take the fd. */
    TRANSIENT;

    val keepsConnecting get() = this == TRANSIENT

    companion object {
        fun of(e: Exception) = if (e is NoVpnPermission) NO_PERMISSION else TRANSIENT
    }
}
