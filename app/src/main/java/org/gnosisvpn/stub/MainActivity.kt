package org.gnosisvpn.stub

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val tunnel: Tunnel = FakeTunnel()
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val listener = Tunnel.Listener { state, detail ->
        render(state)
        log("state -> $state" + (detail?.let { " ($it)" } ?: ""))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        button = findViewById(R.id.connectButton)
        status = findViewById(R.id.statusText)
        log = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        button.setOnClickListener {
            when (tunnel.state) {
                Tunnel.State.DISCONNECTED, Tunnel.State.ERROR -> tunnel.connect()
                else -> tunnel.disconnect()
            }
        }
        tunnel.addListener(listener)
        render(tunnel.state)
        log("app started: $packageName ${packageManager.getPackageInfo(packageName, 0).versionName} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    override fun onDestroy() {
        tunnel.removeListener(listener)
        super.onDestroy()
    }

    private fun render(state: Tunnel.State) {
        status.text = "Status: ${state.name.lowercase()}"
        button.text = when (state) {
            Tunnel.State.DISCONNECTED, Tunnel.State.ERROR -> "Connect"
            else -> "Disconnect"
        }
        button.isEnabled = state != Tunnel.State.DISCONNECTING
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        log.append("${timeFmt.format(Date())}  $msg\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object { const val TAG = "GnosisVpnStub" }
}
