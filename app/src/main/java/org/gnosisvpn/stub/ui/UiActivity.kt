package org.gnosisvpn.stub.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.gnosisvpn.stub.App
import org.gnosisvpn.stub.LogBuffer
import org.gnosisvpn.stub.MainActivity
import org.gnosisvpn.stub.R
import org.gnosisvpn.stub.TunnelService

/** The app's screen: the Compose UI over the engine (App.engine). */
class UiActivity : ComponentActivity() {
    private val exportLogs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { writeLogs(it) } }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) TunnelService.start(this, TunnelService.ACTION_CONNECT)
        else { App.instance.engine.disconnect(); App.instance.ui.showSnack("Gnosis VPN needs permission to set up a VPN connection.") }
    }

    /** Asks for Android's VPN consent the first time, then starts the tunnel. */
    fun connectVpn() {
        val consent = android.net.VpnService.prepare(this)
        if (consent == null) TunnelService.start(this, TunnelService.ACTION_CONNECT) else vpnConsent.launch(consent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = App.instance
        app.activity = this
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        val extra = SettingsExtra(
            rows = listOf(Triple("dev", "Developer screen", "Backends, node identity, raw logs")),
            version = "Gnosis VPN $version" + if (app.engine.simulatesSystem) " · demo engine" else "",
            onRow = { k -> if (k == "dev") startActivity(Intent(this, MainActivity::class.java)) },
        )
        setContent { GnosisApp(app.engine, app.ui, debug, extra) { openVpnSettings() } }   // app.engine is observable state
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (intent?.getBooleanExtra("connect", false) == true) { intent.removeExtra("connect"); connectVpn() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("connect", false)) connectVpn()
    }

    override fun onResume() {
        super.onResume()
        App.instance.activity = this
        // Back from Android's VPN settings: read what the user changed there.
        val eng = App.instance.engine
        if (returningFromSettings) {
            returningFromSettings = false
            App.instance.readLockdown()?.let { eng.sysVpnReturned(it); eng.emit() }
        }
    }

    override fun onDestroy() {
        if (App.instance.activity === this) App.instance.activity = null
        super.onDestroy()
    }

    private var returningFromSettings = false
    fun openVpnSettings() {
        returningFromSettings = !App.instance.engine.simulatesSystem
        runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }.onFailure { toast("VPN settings are not available on this device.") }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun openUrl(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { toast("No browser to open $url") }

    fun share(text: String) = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share your VPN address"))

    fun copy(label: String, text: String) = getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

    fun startLogExport() = runCatching { exportLogs.launch("gnosis-vpn-logs.txt") }.onFailure { toast("No file picker on this device.") }

    private fun writeLogs(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                w.write("Gnosis VPN logs\n\n"); LogBuffer.snapshot().forEach { w.write(it); w.write("\n") }
            }
        }.onSuccess { App.instance.ui.showSnack("Logs saved.") }.onFailure { App.instance.ui.showSnack("Couldn’t save the logs: ${it.message}") }
    }

    fun requestTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                GnosisTileService.component(this), "Gnosis VPN", Icon.createWithResource(this, R.drawable.ic_vpn_key), mainExecutor,
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) App.instance.engine.act("qsAdd")
                else if (result < 0) App.instance.ui.showSnack("Couldn’t add the tile here. Edit Quick Settings to add it.")
            }
        } else App.instance.ui.showSnack("Edit Quick Settings (pencil icon) and drag the Gnosis VPN tile in.")
    }
}

/** The engine's hooks into Android: snackbars, notifications, links, clipboard, share, files. */
class AppHooks(private val ctx: Context) : EngineHooks {
    private val app get() = App.instance
    override fun snack(msg: String) { if (app.activity != null) app.ui.showSnack(msg) else Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    override fun notify(note: Note) = Notifier.post(ctx, note)
    override fun openUrl(url: String) { app.activity?.openUrl(url) }
    override fun share(text: String) { app.activity?.share(text) }
    override fun copy(label: String, text: String) { app.activity?.copy(label, text) }
    override fun openSystemVpnSettings() { app.activity?.openVpnSettings() }
    override fun exportLogs() { app.activity?.startLogExport() }
    override fun requestTile() { app.activity?.requestTile() }
    override fun connectVpn() {
        val a = app.activity
        when {
            a != null -> a.connectVpn()
            android.net.VpnService.prepare(ctx) == null -> TunnelService.start(ctx, TunnelService.ACTION_CONNECT)
            // from the tile or a notification without consent yet: the app asks
            else -> ctx.startActivity(Intent(ctx, UiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("connect", true))
        }
    }
    override fun disconnectVpn() = TunnelService.start(ctx, TunnelService.ACTION_DISCONNECT)
}
