package org.gnosisvpn.stub

import android.content.Context

/**
 * Release builds: no developer log relay. The real one is debug-only (app/src/debug, marked
 * "REMOVE BEFORE PRODUCTION"). With [available] false, this stub hides the relay's bar and its
 * setting. Delete this file together with the debug relay.
 */
object DebugLogRelay {
    const val INTERVAL_SEC = 10L
    val available: Boolean get() = false
    fun isOn(ctx: Context): Boolean = false
    fun setOn(ctx: Context, value: Boolean) {}
    fun start(ctx: Context) {}
}
