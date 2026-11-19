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

    fun channels(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Data running low, payments, internet paused" })
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, UiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun broadcast(ctx: Context, noteId: String, act: String, req: Int) = PendingIntent.getBroadcast(ctx, req,
        Intent(ctx, NotificationActionReceiver::class.java).putExtra("note", noteId).putExtra("act", act),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * A notification action button. One that needs the app's screen opens UiActivity directly:
     * since Android 12 a receiver reached from a notification may not start an activity. The
     * activity runs it once (NoteTaps); the others stay broadcasts to NotificationActionReceiver.
     */
    fun actionIntent(ctx: Context, noteId: String, act: String, req: Int): PendingIntent =
        if (!NoteTaps.needsUi(act, android.net.VpnService.prepare(ctx) != null)) broadcast(ctx, noteId, act, req)
        else {
            NoteTaps.arm(noteId, act)
            PendingIntent.getActivity(ctx, req, NoteTaps.put(Intent(ctx, UiActivity::class.java), noteId, act).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    fun idOf(noteId: String) = noteId.hashCode() and 0x7fffffff or 0x100

    /** A rule fired: post it (heads-up on the alerts channel). */
    fun post(ctx: Context, n: Note) {
        Log.i(TAG, "notify ${n.rule} ${n.title}: ${n.body}")
        val b = Notification.Builder(ctx, ALERTS)
            .setSmallIcon(R.drawable.ic_vpn_key).setContentTitle(n.title).setContentText(n.body)
            .setStyle(Notification.BigTextStyle().bigText(n.body))
            .setContentIntent(openApp(ctx)).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
            .setDeleteIntent(broadcast(ctx, n.id, "gone", idOf(n.id) + 1))
        n.acts.filter { it.second != "nclose" }.forEachIndexed { i, (label, a) ->
            b.addAction(Notification.Action.Builder(null as Icon?, label, actionIntent(ctx, n.id, a, idOf(n.id) + 2 + i)).build())
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(idOf(n.id), b.build()) }
    }

    fun cancel(ctx: Context, noteId: String) = ctx.getSystemService(NotificationManager::class.java).cancel(idOf(noteId))

}

/** Taps on notification actions without UI (and swipes, "gone") go to the engine. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val eng = App.instance.engine
        val noteId = intent.getStringExtra("note") ?: return
        val a = intent.getStringExtra("act") ?: return
        if (a != "gone") tapped(ctx, noteId, a) else { eng.notes.firstOrNull { it.id == noteId }?.gone = true; eng.emit() }
    }

    companion object {
        /** A notification action was tapped: the note is done, then the engine runs the action. */
        fun tapped(ctx: Context, noteId: String, act: String) {
            val eng = App.instance.engine
            eng.notes.firstOrNull { it.id == noteId }?.gone = true
            if (noteId != "ongoing") Notifier.cancel(ctx, noteId)
            eng.act(act)
        }
    }
}

/**
 * Notification actions that open UiActivity. Posting one arms it here, in the process; the
 * activity takes it once. A recreated activity, a task restored from recents or a new process
 * finds nothing armed, so the intent cannot replay the action. The flip side: after the process
 * died, a tap on a notification still in the shade only opens the app.
 * The ongoing notification stays posted and is not rebuilt by a tap, so its buttons stay armed;
 * UiActivity's onCreate guards keep its intent from replaying.
 */
object NoteTaps {
    /** Actions that show a page, and connects that may have to ask for VPN consent. */
    private val UI = setOf("addData", "lockOn", "fundScreen")
    private val CONNECTS = setOf("renew", "bestAvail")
    private val armed = HashSet<String>()

    fun needsUi(act: String, needsConsent: Boolean) = act in UI || (needsConsent && act in CONNECTS)
    @Synchronized fun arm(noteId: String, act: String) { armed += "$noteId/$act" }
    /** The (note, action) [put] into this intent, if armed; disarms it. */
    @Synchronized fun take(note: String?, act: String?): Pair<String, String>? {
        if (note == null || act == null) return null
        val key = "$note/$act"
        val ok = if (note == "ongoing") key in armed else armed.remove(key)
        return if (ok) note to act else null
    }
    fun put(i: Intent, noteId: String, act: String): Intent = i.putExtra("tapNote", noteId).putExtra("tapAct", act)
    fun take(i: Intent?): Pair<String, String>? = take(i?.getStringExtra("tapNote"), i?.getStringExtra("tapAct"))
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
 *   adb shell am broadcast -n org.gnosisvpn.stub.debug/org.gnosisvpn.stub.ui.DebugActReceiver --es a go --es v settings
 * Extras: a (action), v, id, k (action arguments), mode (system|light|dark), codes (true|false).
 * The resulting screen code is logged: "GnosisUi: screen S07 Data low, Auto-renew off".
 */
class DebugActReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = App.instance
        intent.getStringExtra("mode")?.let { app.ui.changeMode(it) }
        intent.getStringExtra("codes")?.let { app.ui.codes = it == "true" }
        intent.getStringExtra("dialog")?.let { app.ui.dialog = it.ifEmpty { null } }
        val a = intent.getStringExtra("a")
        if (a != null) {
            val d = listOf("v", "id", "k").mapNotNull { k -> intent.getStringExtra(k)?.let { k to it } }.toMap()
            when (a) {
                "back" -> goBack(app.engine, app.ui)
                "go" -> app.engine.go(d["v"] ?: "")   // open a page directly, for screenshots
                else -> app.engine.act(a, d)
            }
        }
        app.engine.emit()
        val (code, name) = app.engine.screenCode()
        Log.i("GnosisUi", "screen $code $name")
    }
}
