package org.gnosisvpn.stub

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

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

    override fun onCreate() {
        super.onCreate()
        tunnel.addListener(listener)
    }

    override fun onDestroy() {
        tunnel.removeListener(listener)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_DISCONNECT -> tunnel.disconnect()
            ACTION_CONNECT -> tunnel.connect()
            null -> {
                // Restarted by the system after the process was killed while connected.
                LogBuffer.log("service restarted by the system, reconnecting")
                tunnel.connect()
            }
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

    private fun build(state: Tunnel.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Gnosis VPN (stub)")
            .setContentText("Tunnel ${state.name.lowercase()}")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    companion object {
        const val CHANNEL = "vpn"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "org.gnosisvpn.stub.CONNECT"
        const val ACTION_DISCONNECT = "org.gnosisvpn.stub.DISCONNECT"

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
