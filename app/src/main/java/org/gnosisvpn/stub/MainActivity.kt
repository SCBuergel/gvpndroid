package org.gnosisvpn.stub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {
    private val app get() = App.instance
    private val tunnel get() = app.tunnel
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var statsText: TextView
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val ticker = Handler(Looper.getMainLooper())

    private val stateListener = Tunnel.Listener { state, _ -> render(state) }
    private val logListener: (String) -> Unit = { appendLog(it) }
    private val tick = object : Runnable {
        override fun run() { renderStats(); ticker.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        button = findViewById(R.id.connectButton)
        status = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        log = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        button.setOnClickListener {
            when (tunnel.state) {
                Tunnel.State.DISCONNECTED, Tunnel.State.ERROR -> requestConnect()
                else -> TunnelService.start(this, TunnelService.ACTION_DISCONNECT)
            }
        }
        findViewById<Button>(R.id.importButton).setOnClickListener { scanQr() }
        val backend = findViewById<Button>(R.id.backendButton)
        backend.text = if (app.usesGnosis) "Use WireGuard" else "Use Gnosis VPN"
        backend.isEnabled = app.usesGnosis || GnosisSetup.bundled(this)
        backend.setOnClickListener { switchBackend() }
        findViewById<Button>(R.id.exportButton).apply {
            isEnabled = app.usesGnosis
            setOnClickListener {
                if (!GnosisSetup.hasIdentity(this@MainActivity)) {
                    LogBuffer.log("no identity yet: connect once so the core creates it")
                } else {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json")
                            .putExtra(Intent.EXTRA_TITLE, "gnosisvpn-identity.json"),
                        REQ_EXPORT,
                    )
                }
            }
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }

        LogBuffer.snapshot().forEach { appendLog(it) }
        LogBuffer.addListener(logListener)
        tunnel.addListener(stateListener)
        render(tunnel.state)

        if (savedInstanceState == null) {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            LogBuffer.log("app ${pkg.versionName} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            val cfg = runCatching { app.store.load() }
            cfg.exceptionOrNull()?.let { LogBuffer.log("stored config unreadable: ${it.message}") }
            LogBuffer.log(cfg.getOrNull()?.let { "config: ${describeConfig(it)}" } ?: "no config yet: tap Import QR")
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    override fun onResume() { super.onResume(); ticker.post(tick) }
    override fun onPause() { ticker.removeCallbacks(tick); super.onPause() }

    override fun onDestroy() {
        LogBuffer.removeListener(logListener)
        tunnel.removeListener(stateListener)
        super.onDestroy()
    }

    private fun requestConnect() {
        if (!app.usesGnosis && runCatching { app.store.load() }.getOrNull() == null) {
            LogBuffer.log("no usable config: tap Import QR first")
            return
        }
        // Shows the system "connection request" dialog the first time (or after revocation).
        val consent = VpnService.prepare(this)
        if (consent != null) {
            LogBuffer.log("asking for VPN permission")
            startActivityForResult(consent, REQ_VPN)
        } else {
            TunnelService.start(this, TunnelService.ACTION_CONNECT)
        }
    }

    /** Switches between the WireGuard and Gnosis backends, then restarts the app (the backend is chosen at start). */
    private fun switchBackend() {
        if (tunnel.state != Tunnel.State.DISCONNECTED && tunnel.state != Tunnel.State.ERROR) {
            LogBuffer.log("disconnect first")
            return
        }
        val toGnosis = !app.usesGnosis
        LogBuffer.log(if (toGnosis) "setting up Gnosis VPN (copying 46 MB of data files)..." else "switching to WireGuard (the Gnosis identity is kept)")
        Thread {
            try {
                if (toGnosis) GnosisSetup.enable(this) else GnosisSetup.disable(this)
                runOnUiThread {
                    app.selectBackend()
                    LogBuffer.log("backend: ${if (app.usesGnosis) "gnosis" else "wireguard"}")
                    recreate()
                }
            } catch (e: Exception) {
                LogBuffer.log("switch failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun scanQr() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan the WireGuard config QR code")
            .setBeepEnabled(false)
            .initiateScan()
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                LogBuffer.log("VPN permission granted")
                TunnelService.start(this, TunnelService.ACTION_CONNECT)
            } else {
                LogBuffer.log("VPN permission denied")
            }
            return
        }
        if (requestCode == REQ_EXPORT) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    GnosisSetup.exportIdentity(this, uri)
                    LogBuffer.log("identity exported: keep that file safe, it controls the node's funds")
                } catch (e: Exception) {
                    LogBuffer.log("export failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            } else {
                LogBuffer.log("export cancelled")
            }
            return
        }
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            val text = scan.contents
            if (text == null) {
                LogBuffer.log("QR scan cancelled")
            } else {
                try {
                    val cfg = app.store.import(text)
                    LogBuffer.log("config imported: ${describeConfig(cfg)}")
                    if (tunnel.state == Tunnel.State.CONNECTED) LogBuffer.log("disconnect and reconnect to use it")
                } catch (e: Exception) {
                    LogBuffer.log("QR is not a valid WireGuard config: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun render(state: Tunnel.State) {
        status.text = "Status: ${state.name.lowercase()}"
        button.text = when (state) {
            Tunnel.State.DISCONNECTED, Tunnel.State.ERROR -> "Connect"
            else -> "Disconnect"
        }
        button.isEnabled = state != Tunnel.State.DISCONNECTING
        renderStats()
    }

    private fun renderStats() {
        val s = tunnel.stats()
        statsText.text = if (s == null) "" else {
            val hs = s.lastHandshakeEpochMs?.let { "${(System.currentTimeMillis() - it) / 1000} s ago" } ?: "none yet"
            "rx ${kb(s.rxBytes)}  tx ${kb(s.txBytes)}  handshake $hs"
        }
    }

    private fun kb(b: Long) = if (b < 1024 * 1024) "%.1f KB".format(b / 1024.0) else "%.2f MB".format(b / 1048576.0)

    private fun appendLog(line: String) {
        log.append(line + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val REQ_VPN = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_EXPORT = 3
    }
}
