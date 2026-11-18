package org.gnosisvpn.stub

// =================================================================================================
//  DEBUG BUILDS ONLY. REMOVE BEFORE PRODUCTION.
//
//  Developer log relay. While the app runs, every 10 s it collects the new lines of the app's log
//  (LogBuffer: the app's lines and the core's at the chosen log level). It seals them to the
//  developer's key and posts them to a private ntfy.sh topic, which the developer reads with
//  scripts/relay-read.py. It is ON BY DEFAULT and the user can turn it off: a bar at the top of
//  every screen, and Settings → Logs.
//
//  Why only in debug builds: the log shows addresses, exits and network details, and the
//  topic and key are baked into the APK. Shipping this to users would send their logs to a
//  developer without a real consent flow. A production build must not do that.
//  - This file and DebugLogRelayCore.kt are in app/src/debug, so release builds do not contain
//    them. app/src/release/.../DebugLogRelay.kt is a no-op stub (available = false), which hides
//    the bar and the setting.
//  - The topic and key come from ~/.config/gvpndroid-secrets at build time (app/build.gradle.kts),
//    for the debug build type only. They are not in the repository.
//  To remove it for production: delete both DebugLogRelay files, DebugLogRelayCore.kt, its test
//  in app/src/testDebug, and the build.gradle.kts block marked "DEBUG ONLY". Then delete the
//  calls the compiler reports (App.onCreate, Root.DebugLogBar, the Settings row).
// =================================================================================================

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object DebugLogRelay {
    const val INTERVAL_SEC = 10L
    private const val PREFS = "debug_log_relay"
    private const val MAX_PENDING = 6000
    private const val BACKOFF_429_MS = 15 * 60_000L

    private val topic = BuildConfig.DEBUG_LOG_TOPIC
    private val key: ByteArray? = runCatching { Base64.getDecoder().decode(BuildConfig.DEBUG_LOG_PUBKEY).takeIf { it.size == 32 } }.getOrNull()

    /** False when this build was made without the relay's topic and key; the UI then hides the relay. */
    val available: Boolean get() = topic.isNotEmpty() && key != null

    private lateinit var app: Context
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "debug-log-relay").apply { isDaemon = true } }
    private var started = false
    @Volatile private var on = true

    // Touched only on the relay thread.
    private val queue = DebugLogRelayCore.Queue(MAX_PENDING)
    private var failing = false
    private var blockedUntil = 0L

    fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

    fun setOn(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", value).apply()
        on = value
        LogBuffer.log(if (value) "${DebugLogRelayCore.OWN} on (encrypted log to the developer every $INTERVAL_SEC s)" else "${DebugLogRelayCore.OWN} off (turned off by the user)")
        if (!value) exec.execute { queue.reset(LogBuffer.count()) }
    }

    /** Called once from App.onCreate. */
    @Synchronized
    fun start(ctx: Context) {
        if (started || !available) return
        started = true
        app = ctx.applicationContext
        on = isOn(app)
        LogBuffer.log("debug build: log relay ${if (on) "on (encrypted log to the developer every $INTERVAL_SEC s; turn off in the bar at the top or in Settings)" else "off"}")
        exec.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { LogBuffer.log("${DebugLogRelayCore.OWN} ${it.javaClass.simpleName}: ${it.message}") } },
            INTERVAL_SEC, INTERVAL_SEC, TimeUnit.SECONDS)
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun budget() = DebugLogRelayCore.Budget(prefs().getFloat("tokens", DebugLogRelayCore.Budget.CAP.toFloat()).toDouble(), prefs().getLong("at", 0))
    private fun save(b: DebugLogRelayCore.Budget) = prefs().edit().putFloat("tokens", b.tokens.toFloat()).putLong("at", b.at).apply()

    /** Random id per install, so messages from several devices can be told apart. Not tied to the user. */
    private fun installId(): String = prefs().getString("id", null) ?: java.util.UUID.randomUUID().toString().take(8).also { prefs().edit().putString("id", it).apply() }

    private fun tick() {
        if (!on) { queue.reset(LogBuffer.count()); return }
        val (fresh, next, lost) = LogBuffer.since(queue.cursor)
        queue.take(fresh, next, lost)
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return
        val b = budget().also { it.refill(now) }
        val header = "gvpndroid ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · id ${installId()}"
        var lastCode = 0
        val outcome = queue.flush(header, b) { plain, n ->
            post("gvpndroid log ${installId()} #$n", DebugLogRelayCore.seal(plain, key!!)).also { lastCode = it }
        }
        when (outcome) {
            DebugLogRelayCore.Outcome.SENT -> if (failing) { failing = false; LogBuffer.log("${DebugLogRelayCore.OWN} sending works again") }
            DebugLogRelayCore.Outcome.LIMITED -> {
                blockedUntil = now + BACKOFF_429_MS
                LogBuffer.log("${DebugLogRelayCore.OWN} ntfy.sh refused (429, daily limit for this IP); trying again in 15 min")
            }
            DebugLogRelayCore.Outcome.FAILED -> if (!failing) {
                failing = true
                LogBuffer.log("${DebugLogRelayCore.OWN} sending failed (${if (lastCode < 0) "no connection" else "HTTP $lastCode"}); keeping the lines and trying again")
            }
            else -> {}
        }
        save(b)
    }

    /** POSTs [body] to the topic; returns the HTTP status, or -1 without a connection. */
    private fun post(title: String, body: String): Int = runCatching {
        val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
            c.setRequestProperty("Title", title)
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
        } finally { c.disconnect() }
    }.getOrDefault(-1)
}
