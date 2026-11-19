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

    /** The backend this screen was built for; a switch deferred until the tunnel was down rebuilds it. */
    private val shownGnosis = App.instance.usesGnosis
    private val stateListener = Tunnel.Listener { state, _ -> if (app.usesGnosis != shownGnosis) recreate() else render(state) }
    /** The tunnel [stateListener] is on (App.tunnel changes with the backend). */
    private var listened: Tunnel? = null
    private val logListener: (String) -> Unit = { appendLog(it) }
    private val tick = object : Runnable {
        override fun run() { renderStats(); renderFunding(); renderLocation(); ticker.postDelayed(this, 1000) }
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
        // Gnosis needs both the bundled data files and the Rust core library in this APK.
        backend.isEnabled = app.usesGnosis || app.canUseGnosis()
        backend.setOnClickListener { switchBackend() }
        findViewById<android.view.View>(R.id.gnosisRow).visibility =
            if (app.usesGnosis) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<Button>(R.id.locationButton).setOnClickListener { pickLocation() }
        findViewById<Button>(R.id.logButton).apply {
            text = "Log: ${LogLevel.get(this@MainActivity).name.lowercase()}"
            setOnClickListener {
                val level = LogLevel.get(this@MainActivity).next()
                LogLevel.set(this@MainActivity, level)
                tunnel.setLogLevel(level)
                text = "Log: ${level.name.lowercase()}"
                LogBuffer.log("log level ${level.name.lowercase()}")
            }
        }
        findViewById<Button>(R.id.exportButton).apply {
            isEnabled = app.usesGnosis
            setOnClickListener {
                if (!app.hasNodeIdentity()) {
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

        showRecentLog()
        LogBuffer.addListener(logListener)
        listened = tunnel.also { it.addListener(stateListener) }
        render(tunnel.state)

        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        previewFunding = intent.getStringExtra("preview_funding_address")?.takeIf { debuggable }?.let {
            Tunnel.FundingRequest(it, "preview only")
        }
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
        listened?.removeListener(stateListener)
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

    /** Lists the exits the core has discovered; picking one connects (or reconnects) there. */
    private fun pickLocation() {
        val list = tunnel.locations
        if (list.isEmpty()) {
            LogBuffer.log("no locations yet: the core is still starting (watch the log)")
            return
        }
        val current = tunnel.selectedLocation
        android.app.AlertDialog.Builder(this)
            .setTitle("Location")
            .setSingleChoiceItems(list.map { it.label }.toTypedArray(), list.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                tunnel.selectLocation(list[which])
                renderLocation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderLocation() {
        if (!app.usesGnosis) return
        val label = tunnel.selectedLocation?.label ?: if (tunnel.locations.isEmpty()) "loading…" else "choose"
        findViewById<Button>(R.id.locationButton).text = "Location: $label"
    }

    /** Switches between the WireGuard and Gnosis backends in place (data files are copied off the main thread). */
    private fun switchBackend() {
        if (!App.canSwapBackend(tunnel.state)) {
            LogBuffer.log("disconnect first")
            return
        }
        val toGnosis = !app.usesGnosis
        LogBuffer.log(if (toGnosis) "setting up Gnosis VPN (copying 46 MB of data files)..." else "switching to WireGuard (the Gnosis identity is kept)")
        Thread {
            try {
                app.switchBackend(toGnosis)
                runOnUiThread {
                    // The tunnel may have been connected during the copy: swap only once it is down.
                    if (app.selectBackendWhenDown()) {
                        LogBuffer.log("backend: ${if (app.usesGnosis) "gnosis" else "wireguard"}")
                        recreate()
                    } else {
                        LogBuffer.log("backend switch takes effect after disconnecting (tunnel is ${tunnel.state})")
                    }
                }
            } catch (e: Throwable) {
                // Throwable: a missing native library surfaces as UnsatisfiedLinkError, an Error.
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
                    app.exportNodeIdentity(uri)
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

    private var shownFunding: Tunnel.FundingRequest? = null
    /** Debug builds only: a funding request to preview the QR without a waiting node. */
    private var previewFunding: Tunnel.FundingRequest? = null

    /** QR code of the node address while a new node waits for funds (plain address: any wallet can scan it). */
    private fun renderFunding() {
        val request = tunnel.fundingRequest ?: previewFunding
        if (request == shownFunding) return
        shownFunding = request
        val box = findViewById<android.view.View>(R.id.fundingBox)
        if (request == null) { box.visibility = android.view.View.GONE; return }
        val qr = com.journeyapps.barcodescanner.BarcodeEncoder()
            .encodeBitmap(request.address, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600)
        findViewById<android.widget.ImageView>(R.id.fundingQr).apply {
            setImageBitmap(qr)
            setOnClickListener { copyAddress(request.address) }
        }
        findViewById<TextView>(R.id.fundingText).apply {
            text = "Fund this node on Gnosis Chain (tap to copy):\n${request.address}\nneeds ${request.need}"
            setOnClickListener { copyAddress(request.address) }
        }
        box.visibility = android.view.View.VISIBLE
    }

    private fun copyAddress(address: String) {
        getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("node address", address))
        LogBuffer.log("node address copied")
    }

    private fun kb(b: Long) = if (b < 1024 * 1024) "%.1f KB".format(b / 1024.0) else "%.2f MB".format(b / 1048576.0)

    /** Lines currently in the log view. Each append re-lays out the whole text, so keep it short. */
    private var shownLines = 0

    private fun showRecentLog() {
        val recent = LogBuffer.snapshot().takeLast(SHOWN_MIN)
        log.text = if (recent.isEmpty()) "" else recent.joinToString("\n", postfix = "\n")
        shownLines = recent.size
    }

    private fun appendLog(line: String) {
        if (shownLines >= SHOWN_MAX) showRecentLog()
        log.append(line + "\n")
        shownLines++
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    companion object {
        private const val REQ_VPN = 1
        private const val REQ_NOTIFY = 2
        private const val REQ_EXPORT = 3
        private const val SHOWN_MIN = 300
        private const val SHOWN_MAX = 600
    }
}
