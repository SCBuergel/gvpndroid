package org.gnosisvpn.stub

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.gnosisvpn.stub.ui.ongoingNote

/**
 * Foreground service that owns the tunnel's lifetime and shows the persistent notification.
 * The actual VpnService is the library's GoBackend.VpnService; this service keeps the
 * process prioritised and gives the user a visible Disconnect action.
 */
class TunnelService : Service() {
    private val tunnel get() = App.instance.tunnel

    private val listener = Tunnel.Listener { state, _ ->
        when (state) {
            Tunnel.State.DISCONNECTED, Tunnel.State.ERROR -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> notify(state)
        }
    }

    /** The tunnel [listener] was added to: App.tunnel may be another one by onDestroy. */
    private var listened: Tunnel? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        listened = tunnel.also { it.addListener(listener) }
    }

    override fun onDestroy() {
        running = false
        listened?.removeListener(listener); listened = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()   // first: startForegroundService requires it even when we stop at once
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val owns = runTunnelCommand(intent?.action, tunnel, prefs.getBoolean(WANTED, false)) {
            prefs.edit().putBoolean(WANTED, it).commit()   // read back by a restart after a kill
        }
        if (!owns) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val n = build(tunnel.state)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // E.g. background start restrictions; the VpnService itself keeps running regardless.
            LogBuffer.log("could not start foreground service: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun notify(state: Tunnel.State) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, build(state))
    }

    private fun build(state: Tunnel.State): Notification = build(this, state)

    companion object {
        @Volatile private var running = false

        /** The ongoing notification: what the app shows about the connection, with its actions. */
        fun build(ctx: Context, state: Tunnel.State): Notification {
            val note = runCatching { App.instance.engine.ongoingNote() }.getOrNull()
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, org.gnosisvpn.stub.ui.UiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentTitle(note?.title ?: if (state == Tunnel.State.CONNECTED) "Connected" else "Gnosis VPN")
                .setContentText(note?.body ?: "Connecting…")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            val acts = note?.acts ?: listOf("Disconnect" to "disconnect")
            acts.forEachIndexed { i, (label, a) ->
                val pi = PendingIntent.getBroadcast(ctx, 50 + i,
                    Intent(ctx, org.gnosisvpn.stub.ui.NotificationActionReceiver::class.java).putExtra("note", "ongoing").putExtra("act", a),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(Notification.Action.Builder(null, label, pi).build())
            }
            return b.build()
        }

        /** Re-posts the ongoing notification after the app's view of the connection changed. */
        fun refresh(ctx: Context) {
            if (!running) return
            runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, build(ctx, App.instance.tunnel.state)) }
        }

        const val CHANNEL = "vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "org.gnosisvpn.stub.CONNECT"
        const val ACTION_DISCONNECT = "org.gnosisvpn.stub.DISCONNECT"
        private const val PREFS = "tunnel_service"
        private const val WANTED = "wanted"

        fun start(context: Context, action: String) {
            val i = Intent(context, TunnelService::class.java).setAction(action)
            try {
                context.startForegroundService(i)
            } catch (e: Exception) {
                LogBuffer.log("could not start tunnel service: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
