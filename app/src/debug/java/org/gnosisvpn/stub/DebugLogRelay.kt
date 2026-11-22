package org.gnosisvpn.stub

// =================================================================================================
//  DEBUG BUILDS ONLY. REMOVE BEFORE PRODUCTION.
//
//  Developer log relay. While the app runs, every 10 s it collects the new lines of the app's log
//  and sends them every 30 s for the first 15 minutes, then as ntfy's daily limit allows
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
    /** How often the log is collected and the relay may send. */
    const val TICK_SEC = 10L
    /** For the first [START_MIN] minutes after the app starts it sends at most every [INTERVAL_SEC]
     *  (problem lines at once), later as the daily budget allows (every few minutes). */
    const val INTERVAL_SEC = 30L
    const val START_MIN = 15L
    /** What the bar, the splash and Settings say. */
    const val SCHEDULE = "every 30 s for the first 15 min, then every few minutes"
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
    private var startedAt = 0L
    private var lastSentAt = 0L
    private var blockedUntil = 0L

    fun isOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

    fun setOn(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", value).apply()
        on = value
        LogBuffer.log(if (value) "${DebugLogRelayCore.OWN} on (encrypted log to the developer, $SCHEDULE)" else "${DebugLogRelayCore.OWN} off (turned off by the user)")
        if (!value) exec.execute { queue.reset(LogBuffer.count()) }
    }

    /** Called once from App.onCreate. */
    @Synchronized
    fun start(ctx: Context) {
        if (started || !available) return
        started = true
        app = ctx.applicationContext
        startedAt = System.currentTimeMillis()
        on = isOn(app)
        LogBuffer.log("debug build: log relay ${if (on) "on (encrypted log to the developer, $SCHEDULE; turn off in the bar at the top or in Settings)" else "off"}")
        exec.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { LogBuffer.log("${DebugLogRelayCore.OWN} ${it.javaClass.simpleName}: ${it.message}") } },
            TICK_SEC, TICK_SEC, TimeUnit.SECONDS)
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun budget() = DebugLogRelayCore.Budget(prefs().getFloat("tokens", DebugLogRelayCore.Budget.CAP.toFloat()).toDouble(), prefs().getLong("at", 0))
    private fun save(b: DebugLogRelayCore.Budget) = prefs().edit().putFloat("tokens", b.tokens.toFloat()).putLong("at", b.at).apply()

    /** Random id per install, so messages from several devices can be told apart. Not tied to the user. */
    private fun installId(): String = prefs().getString("id", null) ?: java.util.UUID.randomUUID().toString().take(8).also { prefs().edit().putString("id", it).apply() }

    /**
     * Developer screen, "Send logs now": sends everything collected so far at once, outside the
     * schedule (it still respects ntfy's limit). [done] gets a one-line result, on the relay thread.
     */
    fun sendNow(done: (String) -> Unit) {
        if (!available || !started) { done("the log relay is not in this build"); return }
        if (!on) { done("the log relay is off: turn it on first (bar at the top, or Settings → Logs)"); return }
        LogBuffer.log("${DebugLogRelayCore.OWN} send logs now (asked on the developer screen)")
        exec.execute {
            val r = runCatching { tick(force = true) }.getOrElse { DebugLogRelayCore.Outcome.FAILED }
            done(when (r) {
                DebugLogRelayCore.Outcome.SENT, DebugLogRelayCore.Outcome.NOTHING -> "logs sent to the developer (id ${installId()})"
                DebugLogRelayCore.Outcome.LIMITED -> "ntfy.sh refused (daily limit for this IP); try again later"
                DebugLogRelayCore.Outcome.BUDGET -> "today's sending budget is used up; the lines go out as it refills"
                else -> "sending failed (no connection?); the lines are kept and sent later"
            })
        }
    }

    private fun tick(force: Boolean = false): DebugLogRelayCore.Outcome {
        if (!on) { queue.reset(LogBuffer.count()); return DebugLogRelayCore.Outcome.NOTHING }
        val (fresh, next, lost) = LogBuffer.since(queue.cursor)
        queue.take(fresh, next, lost)
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return DebugLogRelayCore.Outcome.LIMITED
        // Start window: at most every INTERVAL_SEC, unless a line looks like a problem.
        if (!force && !DebugLogRelayCore.mayFlush(now, startedAt, lastSentAt, queue.hasUrgent(), START_MIN, INTERVAL_SEC)) return DebugLogRelayCore.Outcome.NOTHING
        val b = budget().also { it.refill(now) }
        val header = "gvpndroid ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · id ${installId()}"
        var lastCode = 0
        val outcome = queue.flush(header, b, force) { plain, n, attachment ->
            post("gvpndroid log ${installId()} #$n" + if (attachment) " (attachment)" else "", DebugLogRelayCore.seal(plain, key!!), attachment).also { lastCode = it }
        }
        when (outcome) {
            DebugLogRelayCore.Outcome.SENT -> {
                lastSentAt = now
                if (failing) { failing = false; LogBuffer.log("${DebugLogRelayCore.OWN} sending works again") }
            }
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
        return outcome
    }

    /** POSTs [body] to the topic; returns the HTTP status, or -1 without a connection. */
    private fun post(title: String, body: String, attachment: Boolean = false): Int = runCatching {
        val c = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
        try {
            c.requestMethod = if (attachment) "PUT" else "POST"; c.doOutput = true
            c.connectTimeout = 8000; c.readTimeout = if (attachment) 60_000 else 8000
            c.setRequestProperty("Title", title)
            if (attachment) c.setRequestProperty("Filename", "gvpndroid-log.sealed")
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
        } finally { c.disconnect() }
    }.getOrDefault(-1)
}
