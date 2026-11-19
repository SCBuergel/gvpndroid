package org.gnosisvpn.stub

/**
 * What one TunnelService start command does to the tunnel. [action] null is the system's restart
 * of the sticky service after the process died: it reconnects only if the user's last command
 * was a connect ([wanted], persisted by [setWanted]). Returns whether the service has a tunnel to
 * own afterwards; when not, the tunnel reports nothing more, so the service must stop itself.
 */
internal fun runTunnelCommand(action: String?, tunnel: Tunnel, wanted: Boolean, setWanted: (Boolean) -> Unit): Boolean {
    when (action) {
        TunnelService.ACTION_DISCONNECT -> { setWanted(false); tunnel.disconnect() }
        TunnelService.ACTION_CONNECT -> { setWanted(true); tunnel.connect() }
        null -> if (wanted) {
            LogBuffer.log("service restarted by the system, reconnecting")
            tunnel.connect()
        } else {
            LogBuffer.log("service restarted by the system; the last command was a disconnect, staying off")
        }
    }
    // connect(), and disconnect() with something to stop, leave DISCONNECTED/ERROR before returning;
    // the service listener stops the service once that work ends.
    return tunnel.state != Tunnel.State.DISCONNECTED && tunnel.state != Tunnel.State.ERROR
}
