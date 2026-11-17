package org.gnosisvpn.stub

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** App-wide log shown on screen and mirrored to logcat (tag GnosisVpnStub). Never log keys. */
object LogBuffer {
    const val TAG = "GnosisVpnStub"
    private const val MAX_LINES = 2000
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) = append(msg, toLogcat = true)

    private fun append(msg: String, toLogcat: Boolean) {
        if (toLogcat) Log.i(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        main.post { listeners.forEach { it(line) } }
    }

    /** A line from the Rust core: shown on screen only (the core writes logcat itself). */
    fun logCore(msg: String) = append("core ${msg.trim()}", toLogcat = false)

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    fun addListener(l: (String) -> Unit) { listeners += l }
    fun removeListener(l: (String) -> Unit) { listeners -= l }
}
