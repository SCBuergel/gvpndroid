package org.gnosisvpn.stub.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import org.gnosisvpn.stub.App
import org.gnosisvpn.stub.R

/*
 * The Android side of the new UI: real system notifications following the demo's rules (with
 * actions), the ongoing VPN notification, the Quick Settings tile, and (debug builds) a broadcast
 * receiver that drives the engine from adb, so every state can be reached in redroid.
 */

object Notifier {
    const val ALERTS = "alerts"
    const val ONGOING_ID = 7
    private const val TAG = "GnosisUi"
    /** Demo controls → "Rule notes in notifications": the rule code as the notification's sub text. */
    @Volatile var ruleNotes = true
    private var lastOngoing: String? = null

    fun channels(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Data running low, payments, internet paused" })
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, UiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun actionIntent(ctx: Context, noteId: String, act: String, req: Int) = PendingIntent.getBroadcast(ctx, req,
        Intent(ctx, NotificationActionReceiver::class.java).putExtra("note", noteId).putExtra("act", act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun idOf(noteId: String) = noteId.hashCode() and 0x7fffffff or 0x100

    /** A rule fired: post it (heads-up on the alerts channel). */
    fun post(ctx: Context, n: Note) {
        Log.i(TAG, "notify ${n.rule} ${n.title}: ${n.body}")
        val code = n.code ?: RULES.firstOrNull { it.k == n.rule }?.code?.split(" · ")?.last()
        val b = Notification.Builder(ctx, ALERTS)
            .setSmallIcon(R.drawable.ic_vpn_key).setContentTitle(n.title).setContentText(n.body)
            .setStyle(Notification.BigTextStyle().bigText(n.body))
            .setContentIntent(openApp(ctx)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .setDeleteIntent(actionIntent(ctx, n.id, "gone", idOf(n.id) + 1))
        if (ruleNotes && code != null) b.setSubText(if (n.proposed) "Proposed" else code)
        n.acts.filter { it.second != "nclose" }.forEachIndexed { i, (label, a) ->
            b.addAction(Notification.Action.Builder(null as Icon?, label, actionIntent(ctx, n.id, a, idOf(n.id) + 2 + i)).build())
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(idOf(n.id), b.build()) }
    }

    fun cancel(ctx: Context, noteId: String) = ctx.getSystemService(NotificationManager::class.java).cancel(idOf(noteId))

    /** The ongoing VPN-service notification (demo engine; the Gnosis backend's TunnelService posts its own). */
    fun ongoing(ctx: Context, n: Note?) {
        val key = n?.let { it.title + "|" + it.body + "|" + it.acts }
        if (key == lastOngoing) return
        lastOngoing = key
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (n == null) { nm.cancel(ONGOING_ID); return }
        val b = Notification.Builder(ctx, org.gnosisvpn.stub.TunnelService.CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_key).setContentTitle(n.title).setContentText(n.body)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp(ctx)).setCategory(Notification.CATEGORY_SERVICE)
        n.acts.forEachIndexed { i, (label, a) -> b.addAction(Notification.Action.Builder(null as Icon?, label, actionIntent(ctx, "ongoing", a, 50 + i)).build()) }
        runCatching { nm.notify(ONGOING_ID, b.build()) }
    }
}

/** Taps on notification actions (and swipes, "gone") go to the engine. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val eng = App.instance.engine
        val noteId = intent.getStringExtra("note") ?: return
        val a = intent.getStringExtra("act") ?: return
        eng.notes.firstOrNull { it.id == noteId }?.gone = true
        if (a != "gone") {
            if (noteId != "ongoing") Notifier.cancel(ctx, noteId)
            // Actions that open a page need the app in front.
            if (a in setOf("addData", "lockOn", "fundScreen")) ctx.startActivity(Intent(ctx, UiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            eng.act(a)
        } else eng.emit()
    }
}

class GnosisTileService : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() { App.instance.engine.act("qsToggle"); refresh() }
    override fun onTileAdded() { val e = App.instance.engine; if (!e.qsTile) e.act("qsAdd") }
    private fun refresh() {
        val t = qsTile ?: return
        val e = App.instance.engine
        t.state = if (e.conn == Conn.IDLE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        t.label = "Gnosis VPN"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = if (e.conn == Conn.IDLE) "Off" else e.statusTxt()
        t.updateTile()
    }
    companion object {
        fun component(ctx: Context) = ComponentName(ctx, GnosisTileService::class.java)
        fun update(ctx: Context) = runCatching { requestListeningState(ctx, component(ctx)) }
    }
}

/**
 * Debug builds only (registered in src/debug): drives the engine from adb for testing in redroid.
 *   adb shell am broadcast -n org.gnosisvpn.stub.debug/org.gnosisvpn.stub.ui.DebugActReceiver --es a preset --es v s07
 * Extras: a (action), v, id, k (action arguments), mode (system|light|dark), codes (true|false),
 * engine (demo|gnosis).
 * The resulting screen code is logged: "GnosisUi: screen S07 Data low, Auto-renew off".
 */
class DebugActReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = App.instance
        intent.getStringExtra("engine")?.let { if (it != (if (app.engine.simulatesSystem) "demo" else "gnosis")) app.useEngine(it) }
        intent.getStringExtra("mode")?.let { app.ui.changeMode(it) }
        intent.getStringExtra("codes")?.let { app.ui.codes = it == "true" }
        intent.getStringExtra("dialog")?.let { app.ui.dialog = it.ifEmpty { null } }
        val a = intent.getStringExtra("a")
        if (a != null) {
            val d = listOf("v", "id", "k").mapNotNull { k -> intent.getStringExtra(k)?.let { k to it } }.toMap()
            if (a == "back") goBack(app.engine, app.ui) else app.engine.act(a, d)
        }
        app.engine.emit()
        val (code, name) = app.engine.screenCode()
        Log.i("GnosisUi", "screen $code $name")
    }
}
