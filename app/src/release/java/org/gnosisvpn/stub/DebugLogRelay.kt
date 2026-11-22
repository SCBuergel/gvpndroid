package org.gnosisvpn.stub

import android.content.Context

/**
 * Release builds: no developer log relay. The real one is debug-only (app/src/debug, marked
 * "REMOVE BEFORE PRODUCTION"). With [available] false, this stub hides the relay's bar and its
 * setting. Delete this file together with the debug relay.
 */
object DebugLogRelay {
    /** How often the log is collected and the relay may send. */
    const val TICK_SEC = 10L
    /** For the first [START_MIN] minutes after the app starts it sends at most every [INTERVAL_SEC]
     *  (problem lines at once), later as the daily budget allows (every few minutes). */
    const val INTERVAL_SEC = 30L
    const val START_MIN = 15L
    /** What the bar, the splash and Settings say. */
    const val SCHEDULE = "every 30 s for the first 15 min, then every few minutes"
    val available: Boolean get() = false
    fun isOn(ctx: Context): Boolean = false
    fun setOn(ctx: Context, value: Boolean) {}
    fun start(ctx: Context) {}
    fun sendNow(done: (String) -> Unit) = done("the log relay is not in this build")
}
