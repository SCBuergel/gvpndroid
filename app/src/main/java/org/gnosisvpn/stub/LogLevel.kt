package org.gnosisvpn.stub

import android.content.Context

/** How much the on-screen log shows; stored in plain preferences (nothing secret). */
enum class LogLevel(val coreFilter: String) {
    /** The connection story: core decisions, PIX holds, reconnects; warnings from everything else. */
    NORMAL("warn,gnosisvpn=info,gnosis_vpn_lib::core=info,gnosis_vpn_lib::connection=info,hopr_strategy::pix=info,hopr_lib::builder=info,hopr_transport::protocol::stream=off"),
    /** Everything at info, minus the path/probe chatter that floods the log. */
    VERBOSE("info,hopr_transport::path=off,hopr_transport_probe=error,hopr_transport_p2p::swarm=warn"),
    /** Debug for the client and HOPR, info for libraries. */
    DEBUG("debug,hopr_transport::path=off,hopr_transport_probe=info,libp2p=info,libp2p_swarm=info,yamux=info,quinn=info,quinn_proto=info,hickory_proto=info,hickory_resolver=info,h2=info,hyper=info,hyper_util=info,reqwest=info,rustls=info,alloy=info,netlink_proto=info");

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "settings"

        fun get(context: Context): LogLevel =
            runCatching { valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log_level", null)!!) }
                .getOrDefault(NORMAL)

        fun set(context: Context, level: LogLevel) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log_level", level.name).apply()
        }
    }
}
