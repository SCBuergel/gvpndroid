package org.gnosisvpn.stub.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The app's UI state machine, ported from the prototype's fake backend (~/mobile-UI src/engine.js,
 * itself ported from the desktop demo "Gnosis VPN Stage 2.1"), so behaviour, numbers and copy match.
 *
 * The UI only reads the fields below and calls act(name, args). Everything runs on the main thread.
 * Every change ends in emit(), which bumps [rev]; the Compose root reads rev, so the UI recomposes.
 *
 * What is simulated here (time, data use, payments, exits) is what a real backend replaces; see
 * README "New UI". The navigation state (stack, sheet, dialogs) lives here too, as in the prototype,
 * because the engine itself opens sheets and pages (e.g. the renew prompt, "Pay outside the VPN").
 */

const val RATE = 0.08            // MB per second while connected
const val COST = 600.0
const val LOW = 120.0
const val AUTO_AT = 30.0
const val SOON = RATE * 300
const val PAY = 3000.0
const val BASE_CLOCK = 9 * 3600 + 41 * 60   // the simulated clock starts at 09:41
const val HOLD_S = 3 * 3600.0              // data left at an exit stays 3 h
const val UPD_V = "1.4.0"
const val SYNC_LEN = 120.0
const val MIN_PATH_VALUE = 0.9

enum class Conn { IDLE, CONNECTING, CONNECTED, RECONNECTING, PAUSED }
enum class Pause { NODATA, OFFLINE, BALANCE, RENEWFAIL }
enum class Tone { OK, WARN, BAD, INFO, LAV }

class Exit(
    val id: String, var city: String, val country: String, var place: String = "",
    var ping: Int, val rel: Int, var spots: Int, val slots: Int = 0, val pv: Double = 1.0, val fr: Int = 0,
    val measured: Boolean = false, val busy: String = "", var full: Boolean = false, var offline: Boolean = false,
    var routable: Boolean = true, val countryCode: String = id,
) {
    fun copy() = Exit(id, city, country, place, ping, rel, spots, slots, pv, fr, measured, busy, full, offline, routable, countryCode)
    /** When the backend last measured this exit (epoch ms), its load (0..1) and whether a check runs now. */
    var checkedAt: Long? = null; var load: Double? = null; var checking = false
}

data class Grade(val k: String, val n: Int, val label: String, val tone: Tone? = null, val sc: Double? = null)

/** An in-app message on the main screen. [text] may contain "{retry}", filled in live. */
data class Flash(val tone: Tone, val lead: String, val text: String, val btns: List<Pair<String, String>> = emptyList())

class Sheet(
    val type: String, val id: String = "",
    val intro: Boolean = false, val renew: Boolean = false, val soon: Boolean = false,
    val check: Boolean = false, val until: Double = 0.0, var dont: Boolean = false,
)

class Note(
    val rule: String, val title: String, val body: String, val acts: List<Pair<String, String>> = emptyList(),
    val silent: Boolean = false, val proposed: Boolean = false, val code: String? = null,
    var at: Double = 0.0, var id: String = "", var gone: Boolean = false,
)

data class Rule(val k: String, val code: String, val name: String, val rule: String, val silent: Boolean = false, val proposed: Boolean = false)

data class Recent(val id: String, val at: Double)
data class FullMsg(val id: String, val alt: String?)
data class Failure(val title: String, val code: String)

val RULES = listOf(
    Rule("u1", "U1", "Plenty of data", "Never. Everything is fine.", silent = true),
    Rule("u2", "U2", "Renewing automatically", "Never. Expected; the app shows it in place.", silent = true),
    Rule("u3", "U3 · N3", "Exit running low", "Only with Auto-renew off."),
    Rule("u4", "U4 · N4", "Balance running low", "Always, also with Auto-renew on."),
    Rule("u5", "U5 · N5", "Balance empty", "Always, also with Auto-renew on."),
    Rule("u6", "U6 · N6", "Internet pauses soon", "Always, when no renewal will happen."),
    Rule("a3", "A3", "Internet paused, no data", "Only with Auto-renew off (journey A3)."),
    Rule("n8", "N8 · N7", "Payment received", "Always. N7 adds the Lockdown reminder."),
    Rule("off", "Proposed", "Exit went offline", "Not defined on the canvas yet.", proposed = true),
    Rule("upd", "Proposed", "Update available", "Once per version, after it has downloaded. Never while connecting.", proposed = true),
)

val REL = mapOf(3 to "Very reliable", 2 to "Reliable", 1 to "Less reliable")
val TIPS = mapOf(
    "fast" to "Fastest exits first, by ping right now.", "rel" to "Exits that stay online longest come first.",
    "recent" to "The last 6 exits you used, newest first.", "alpha" to "Sorted by country name.",
)

/** FNV-1a over UTF-16 code units, as hash32() in the prototype. */
fun hash32(str: String): Int {
    var h = 0x811c9dc5.toInt()
    for (ch in str) { h = h xor ch.code; h *= 0x01000193 }
    return h
}
fun xid(id: String): String = hash32("pub:$id").toUInt().toString(16).padStart(8, '0').take(4)

/* ---------- formatting ---------- */
fun mb(v: Double): String = if (v >= 1000) trimZero(String.format(java.util.Locale.ROOT, "%.1f", v / 1000)) + " GB" else "${max(0, v.roundToInt())} MB"
private fun trimZero(s: String) = if (s.endsWith(".0")) s.dropLast(2) else s
fun p2(n: Double) = floor(n).toInt().toString().padStart(2, '0')
fun fmtT(t: Double): String { val s = BASE_CLOCK + t; return p2(s / 3600 % 24) + ":" + p2(s % 3600 / 60) }
fun dur(m: Int) = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
fun Int.grouped(): String = String.format(java.util.Locale.ENGLISH, "%,d", this)

/** UI hooks the engine calls (set by the activity / app). */
interface EngineHooks {
    fun snack(msg: String) {}
    fun notify(note: Note) {}
    fun openUrl(url: String) {}
    fun share(text: String) {}
    fun copy(label: String, text: String) {}
    fun openSystemVpnSettings() {}
    fun exportLogs() {}
    fun requestTile() {}
    /** Restart the backend after a failure. */
    fun restartBackend() {}
    /** Real backend: ask for VPN consent if needed, then start the tunnel; and stop it. */
    fun connectVpn() {}
    fun disconnectVpn() {}
}

/** Shown while no exit is known yet (the real backend before discovery). */
val NO_EXIT = Exit("", "Best available", "Best available", ping = 0, rel = 0, spots = 0, measured = false)

open class Engine {
    /** Bumped on every change; the Compose root reads it. */
    val rev = mutableIntStateOf(0)
    var hooks: EngineHooks = object : EngineHooks {}

    var exits: MutableList<Exit> = mutableListOf()
    /** The exits a fresh engine starts with (none: the backend reports them). */
    protected open fun initialExits(): List<Exit> = emptyList()

    /* ---- state (S in the prototype) ---- */
    var conn = Conn.IDLE; var pause: Pause? = null; var loc = "de"; var mode = "auto"
    var alloc = mutableMapOf<String, Double>(); var allocN = 0; var free = false
    var balance = 4800.0; var added = 4800.0; var balEp = 0
    var autoRenew = true; var lockdown = true; var alwaysOn = true; var startConnect = true; var topup = false
    var warm = true; var warmEnd = 180.0; var popover = false
    var icons = "node"; var arIntro = false; var askSwitch = true; var arCount = 0; var arPast4 = false; var arDecl = -1
    var t = 0.0; var connSec = 0.0; var speed = 1; var firstRun = true
    var stack = mutableListOf<String>(); var stab = "general"; var sheet: Sheet? = null; var offConfirm = false
    var details = false; var simple = true
    var flash: Flash? = null; var fq = mutableListOf<Flash>(); var ldOffPay = false; var pay: Double? = null
    var offered = false; var better: String? = null
    var sort = "fast"; var q = ""; var openRow: String? = null; var fullMsg: FullMsg? = null; var lost = 0.0; var retryAt = 0.0
    var nextCheck = 40.0; var checking = false; var swapAnim = false; var failRenew = false; var nextFull = false
    var allocAt = mutableMapOf<String, Double>(); var notes = mutableListOf<Note>(); var fired = mutableMapOf<String, Double>()
    var recent = mutableListOf<Recent>(); var ptab = "best"; var pall = false; var relay = 2
    var qsTile = false; var unread = 0; var gate = false; var syncT: Double? = null; var fail: Failure? = null
    var update: String? = null; var askedSoon = -1
    var cStart = 0L; var cDur = 12000L; private var cTok = 0
    var copiedAt = 0L
    var sysPurpose: String? = null; private var sysBefore = false
    /** True in the tests' demo engine: Lockdown controls open the simulated system screen. */
    open val simulatesSystem = false
    /** Data accounting (balance, a chunk of data per exit, renewals) is shown. */
    open val metered = true
    /** Data set aside per exit, in MB (the core's PIX chunk). */
    open val chunk: Double get() = COST
    fun chunkTxt() = mb(chunk)
    /** Renewing is the backend's job (the Gnosis core buys the next chunk by itself): no Auto-renew switch. */
    open val autoRenewFixed = false
    /** A failed renewal is retried on a schedule the engine knows (a countdown is shown); false = the backend retries by itself. */
    open val retryCountdown = true
    /** Log upload to support exists. */
    open val canUploadLogs = true
    /** How far the connect is, 0..1, when the backend reports it; null = time-based. */
    open fun connectFrac(): Float? = null
    /** Live throughput in Mbit/s (down, up) when the backend reports it; null = the visual's own estimate. */
    open fun rates(): Pair<Double, Double>? = null
    /** The balance is known (the backend reported it); unknown counts as enough. */
    open val balanceKnown: Boolean get() = true
    /** The balance is known to cover at most one more block of data: only then is "Add funds" the fix. */
    fun balanceLow(): Boolean = balanceKnown && balance <= chunk
    /** Connected, and the exit holds traffic until it sees this connection's payment on chain (not a failure). */
    open val awaitingPayment: Boolean get() = false
    /** The session's last payment failed (the core retries it). */
    open val paymentFailed: Boolean get() = false
    /** Too little balance for another chunk (and the backend said so). */
    fun short() = balanceKnown && balance < chunk
    /** The balance as shown (data, or tokens while the data estimate is unknown). */
    open fun balanceText(): String = mb(balance)
    /** What to send to the VPN address, from fundingNeeds() ("225.35 wxHOPR and 0.005 xDAI"); null = nothing known. */
    fun fundingAmounts(): String? =
        fundingNeeds().filter { it.third.firstOrNull()?.isDigit() == true }.joinToString(" and ") { "${it.third} ${it.first}" }.ifEmpty { null }
    /** Rows for the "Time remaining" dialog; null = the prototype's. */
    open fun sessionRows(): List<Pair<String, String>>? = null
    /** Received / sent this session in bytes, when known. */
    open fun sessionTotals(): Pair<Long, Long>? = null

    protected val handler = Handler(Looper.getMainLooper())

    /** Called after every change (the app updates the ongoing notification and the tile). */
    var onChange: (() -> Unit)? = null
    fun emit() { rev.intValue++; onChange?.invoke() }
    fun toast(msg: String) = hooks.snack(msg)
    protected fun later(ms: Long, f: () -> Unit) { handler.postDelayed(f, ms) }

    /* ---- navigation stack (Android back stack) ---- */
    fun page(): String? = stack.lastOrNull()
    fun go(p: String) { if (page() != p) stack.add(p) }
    fun home() { stack.clear() }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }

    /* ---- helpers ---- */
    fun ex(id: String): Exit = exits.firstOrNull { it.id == id } ?: exits.firstOrNull() ?: NO_EXIT
    fun exOrNull(id: String?): Exit? = exits.firstOrNull { it.id == id }
    fun usable(e: Exit) = !e.full && !e.offline
    fun left(id: String = loc): Double = alloc[id] ?: 0.0
    open fun minsLeft(v: Double = left()): Int = max(1, (v / RATE / 60).roundToInt())
    /** How long the data left here lasts at the current rate, as a phrase ("about 1 h 20 min"); null = unknown. */
    open fun timeLeft(): String? = "about " + dur(minsLeft())
    fun isOn() = conn == Conn.CONNECTED || conn == Conn.RECONNECTING
    fun heldLeft(id: String): Double { val at = allocAt[id] ?: (t - 600); return max(0.0, HOLD_S - (t - at)) }
    fun warmNow() = warm && t < warmEnd
    fun clock() = fmtT(t)
    fun now() = SystemClock.uptimeMillis()

    fun usePhrase(): String {   // ~3 GB per hour of HD video, ~100 MB per hour of browsing
        val m = balance; if (m <= 0) return "No data left."
        fun f(h: Double): String {
            if (h < 1) { val n = max(1, (h * 60).roundToInt()); return "$n " + if (n == 1) "minute" else "minutes" }
            val r = if (h < 10) Math.round(h * 2) / 2.0 else Math.round(h).toDouble()
            val rs = if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
            return "$rs " + if (r == 1.0) "hour" else "hours"
        }
        val hd = m / 3000; val br = m / 100
        return "Enough for roughly ${f(hd)} of HD video or ${if (br >= 10) "${floor(br).toInt()}+ hours" else f(br)} of browsing."
    }

    /* ---- route grade (as routeGrade() in gnosis_vpn-app) ---- */
    fun grade(e: Exit?): Grade {
        if (e == null || e.full || e.offline || !e.routable || (e.measured && e.slots > 0 && e.spots <= 0 && !(isOn() && e.id == loc)))
            return Grade("hidden", 0, "Unavailable")
        if (e.pv < MIN_PATH_VALUE) return Grade("weak", 1, "Weak route", Tone.BAD, -1.0)
        if (!e.measured) return Grade("unmeasured", 0, "Not measured yet", null, -0.5)
        val own = if (isOn() && e.id == loc) 1 else 0
        val latency = 1 - min(e.ping / 2.0, 1000.0) / 1000
        val capacity = min(1.0, (e.spots + own).toDouble() / e.slots)
        val diversity = min(e.fr, 4) / 4.0
        val sc = 0.5 * latency + 0.3 * capacity + 0.2 * diversity
        val n = if (sc >= 0.75) 4 else if (sc >= 0.5) 3 else if (sc >= 0.25) 2 else 1
        return Grade("ok", n, listOf("", "Poor", "Fair", "Good", "Excellent")[n], if (n >= 3) Tone.OK else if (n == 2) Tone.WARN else Tone.BAD, sc)
    }
    open fun best(except: String? = null): Exit? =
        exits.filter { usable(it) && it.id != except && grade(it).k == "ok" }.maxByOrNull { grade(it).sc ?: -2.0 }

    /* ---- system notifications: texts ---- */
    private val closeAct = "Close" to "nclose"
    protected fun nUpd() = Note("upd", "Update available", "Gnosis VPN $UPD_V is ready. Restarting takes a few seconds.", listOf("Restart to update" to "updNow", "Later" to "nclose"), proposed = true)
    private fun nU1() = Note("u1", "Plenty of data", "Nothing to do, so nothing is sent.", silent = true)
    private fun nU2() = Note("u2", "Renewing automatically", "${chunkTxt()} set aside at ${ex(loc).city}. Shown in the app only.", silent = true)
    private fun nU3() = Note("u3", "Exit running low", "${left().roundToInt()} MB left at ${ex(loc).city}, about ${minsLeft()} min at this rate.", listOf("Renew · ${chunkTxt()}" to "renew", closeAct))
    private fun nU4() = Note("u4", "Balance running low", "${mb(balance)} left. ${usePhrase()}", listOf("Add data" to "addData", closeAct))
    private fun nU5() = Note("u5", "Balance empty", "No data left to renew. Add data to keep browsing.", listOf("Add data" to "addData", closeAct))
    private fun nU6() = Note("u6", "Internet pauses soon", "About ${minsLeft()} min of data left at ${ex(loc).city}.",
        if (balance >= chunk) listOf("Renew · ${chunkTxt()}" to "renew", closeAct) else listOf("Add data" to "addData", closeAct))
    private fun nA3() = Note("a3", "Internet paused", "No data left at ${ex(loc).city}. Your traffic stays private while paused.", listOf("Renew and reconnect" to "renew", closeAct))
    private fun nN8(ld: Boolean) = Note("n8", "Payment received", "3 GB added. Your balance is ${mb(balance)}.${if (ld) " Lockdown is still off." else ""}",
        if (ld) listOf("Turn Lockdown back on" to "lockOn", closeAct) else listOf(closeAct), code = if (ld) "N7" else "N8")
    private fun nOff() = Note("off", "Internet paused", "${ex(loc).city} went offline. The ${lost.roundToInt()} MB left there is lost.", listOf("Connect to best available" to "bestAvail", closeAct), proposed = true)

    fun fire(key: String, n: Note) {
        if (fired.containsKey(key)) return
        fired[key] = t
        n.at = t; n.id = "$key:${t.toInt()}"
        notes.add(0, n)
        if (notes.size > 30) notes.removeAt(notes.size - 1)
        if (!n.silent) { unread++; hooks.notify(n) }
    }

    /* ---- engine ---- */
    fun setFlash(tone: Tone, lead: String, text: String, btns: List<Pair<String, String>> = emptyList()) {
        val f = flash
        if (f != null && f.lead != lead) fq = (listOf(f) + fq.filter { it.lead != lead && it.lead != f.lead }).take(3).toMutableList()
        flash = Flash(tone, lead, text, btns)
    }

    open fun step(dt: Double) {
        for (id in alloc.keys.toList()) if (id != loc && (alloc[id] ?: 0.0) > 0 && heldLeft(id) <= 0) alloc[id] = 0.0
        if (gate) { if (pay != null && t >= pay!!) paymentArrived(); return }
        if (syncT != null) return
        if (conn == Conn.IDLE) { arCount = 0; arDecl = -1 }
        if (conn == Conn.CONNECTED) {
            connSec += dt
            alloc[loc] = max(0.0, left() - RATE * dt)
            val a = left(); val canRenew = balance >= chunk
            if (autoRenew && a <= AUTO_AT && canRenew) autoRenewNow()
            else {
                if (!autoRenew && a <= LOW) fire("u3:$allocN", nU3())
                if (a <= SOON && !(autoRenew && canRenew)) fire("u6:$allocN", nU6())
                // Auto-renew off: ask to renew when the internet is about to pause, once per ${chunkTxt()}
                if (a <= SOON && a > 0 && !autoRenew && canRenew && sheet == null && askedSoon != allocN) {
                    askedSoon = allocN; sheet = Sheet("quota", loc, renew = true, soon = true)
                }
                if (a <= 0) pauseFor(if (canRenew) Pause.NODATA else Pause.BALANCE)
            }
        }
        if (balance > 0 && balance <= chunk) fire("u4:$balEp", nU4())
        if (balance < chunk && ((conn == Conn.CONNECTED && left() <= LOW) || pause == Pause.BALANCE)) fire("u5:$balEp", nU5())
        if (pay != null && t >= pay!!) paymentArrived()
        if (pause == Pause.RENEWFAIL && t >= retryAt) { if (failRenew) retryAt = t + 30 else renewNow() }
        if (conn == Conn.IDLE && mode == "auto" && !checking && page() == null && t >= nextCheck) runCheck()
    }

    fun autoRenewNow() {
        val sh = sheet
        if (sh != null && sh.check) {
            if (t >= sh.until) {
                sheet = null; arDecl = allocN; pauseFor(Pause.NODATA)
                setFlash(Tone.WARN, "Auto-renew paused.", "No answer for 5 minutes. Renew to keep going.", listOf("Renew" to "renew"))
            }
            return
        }
        if (!arPast4 && arCount >= 4 && balance >= chunk * 4) {
            if (arDecl == allocN) { if (left() <= 0) pauseFor(Pause.NODATA); return }
            sheet = Sheet("quota", loc, check = true, until = t + 300); return
        }
        if (failRenew) { pauseFor(Pause.RENEWFAIL); return }
        balance -= chunk; alloc[loc] = left() + chunk; allocN++; allocAt[loc] = t; arCount++
        setFlash(Tone.OK, "Renewed automatically.", "${chunkTxt()} set aside at ${ex(loc).city}. Tip: switching exits now and then makes your traffic harder to link.")
        fire("u2:$allocN", nU2())
    }

    open fun pauseFor(reason: Pause) {
        conn = Conn.PAUSED; pause = reason; offConfirm = false; flash = null; fq.clear(); better = null
        if (reason == Pause.NODATA && !autoRenew) { fire("a3:$allocN", nA3()); if (balance >= chunk) sheet = Sheet("quota", loc, renew = true) }
        if (reason == Pause.RENEWFAIL) retryAt = t + 30
        if (reason == Pause.OFFLINE) fire("off:${t.toInt()}", nOff())
    }

    open fun runCheck() {
        checking = true
        later(1500) {
            checking = false; nextCheck = t + 60
            exOrNull("at")?.let { if (it.ping > 233) it.ping = 233 }
            val after = best()?.id
            if (conn == Conn.IDLE && mode == "auto" && after != null && after != loc) {
                loc = after; swapAnim = true
                setFlash(Tone.INFO, "${ex(after).city} is now the best exit.", "Auto keeps this up to date until you connect.")
                later(700) { swapAnim = false; emit() }
            }
            emit()
        }
    }

    fun requestConnect(id: String, viaList: Boolean = false) {
        val e = ex(id)
        if (!usable(e)) return
        if (nextFull && viaList && id != loc) {
            e.full = true; nextFull = false; fullMsg = FullMsg(id, best(id)?.id); emit(); return
        }
        if (page() == "pick") back()
        val needs = !(left(id) > 0) && !free
        if (needs && short()) { sheet = Sheet("nobal", id); emit(); return }
        if (popover && needs && autoRenew && !arIntro) { sheet = Sheet("quota", id, intro = true); emit(); return }   // once per installation
        if (popover && !(left(id) > 0) && !autoRenew) { sheet = Sheet("quota", id); emit(); return }                     // off: ask every time
        doConnect(id)
    }
    fun connLbl() = if (!popover && !free && !(left(loc) > 0)) "Connect · ${chunkTxt()}" else "Connect"

    open fun doConnect(id: String) {
        val fromPause = conn == Conn.PAUSED
        home(); sheet = null; fullMsg = null; flash = null; fq.clear(); offConfirm = false; better = null
        loc = id; conn = Conn.CONNECTING; pause = null; cStart = now(); cDur = (12000 / min(speed, 4)).toLong()
        val tok = ++cTok
        later(cDur) { if (cTok == tok && conn == Conn.CONNECTING) finishConnect(fromPause); emit() }
        emit()
    }
    protected fun cancelConnect() { cTok++ }

    open fun finishConnect(fromPause: Boolean) {
        val id = loc
        if (!(left(id) > 0)) {
            if (free) free = false else balance -= chunk
            alloc[id] = chunk; allocN++; allocAt[id] = t
        }
        conn = Conn.CONNECTED; connSec = 0.0; firstRun = false
        recent = (listOf(Recent(id, t)) + recent.filter { it.id != id }).take(6).toMutableList()
        fire("u1", nU1())
        if (fromPause) backOnline()
    }

    fun backOnline() {
        if (!autoRenew && !offered) {
            offered = true
            setFlash(Tone.OK, "Back online.", "${chunkTxt()} set aside at ${ex(loc).city}. Renew automatically next time?", listOf("Turn on Auto-renew" to "arOn", "Not now" to "dismiss"))
        } else setFlash(Tone.OK, "Back online.", "${chunkTxt()} set aside at ${ex(loc).city}.")
    }

    fun renewNow() {
        if (balance < chunk) { if (conn == Conn.PAUSED) pause = Pause.BALANCE else sheet = Sheet("nobal", loc); emit(); return }
        if (failRenew) { if (conn != Conn.PAUSED || pause != Pause.RENEWFAIL) pauseFor(Pause.RENEWFAIL) else retryAt = t + 30; emit(); return }
        val wasPaused = conn == Conn.PAUSED
        balance -= chunk; alloc[loc] = left() + chunk; allocN++; allocAt[loc] = t; flash = null; fq.clear()
        if (wasPaused) {
            conn = Conn.RECONNECTING; pause = null; offConfirm = false
            later(1800) { if (conn == Conn.RECONNECTING) { conn = Conn.CONNECTED; backOnline() }; emit() }
        } else setFlash(Tone.OK, "Renewed.", "${chunkTxt()} set aside at ${ex(loc).city}.")
        emit()
    }

    open fun disconnect() {
        conn = Conn.IDLE; pause = null; offConfirm = false; flash = null; fq.clear(); better = null; cancelConnect(); nextCheck = t + 20
        emit()
    }

    fun paymentArrived() {
        if (gate) { gate = false; pay = null; balance = PAY; added = PAY; balEp++; home(); startSync(); return }
        pay = null; balance += PAY; added += PAY; balEp++
        fire("n8:${t.toInt()}", nN8(ldOffPay))
        val bal = mb(balance)
        if (ldOffPay) setFlash(Tone.OK, "Payment received.", "3 GB added; your balance is $bal. You can connect again.")
        else if (isOn()) {
            if (autoRenew) setFlash(Tone.OK, "Payment received.", "3 GB added; your balance is $bal. Auto-renew uses it when this exit runs low.")
            else setFlash(Tone.OK, "Payment received.", "3 GB added; your balance is $bal. Renew automatically when an exit runs low?", listOf("Turn on Auto-renew" to "arOn", "Not now" to "dismiss"))
        } else if (conn == Conn.PAUSED) { setFlash(Tone.OK, "Payment received.", "3 GB added; your balance is $bal."); if (pause == Pause.BALANCE) pause = Pause.NODATA }
        else if (autoRenew) setFlash(Tone.OK, "Payment received.", "3 GB added; your balance is $bal. Connect when you’re ready; renewals then run on their own.")
        else setFlash(Tone.OK, "Payment received.", "3 GB added; your balance is $bal. Connecting sets aside ${chunkTxt()} of it.", listOf("Turn on Auto-renew" to "arOn", "Not now" to "dismiss"))
    }

    fun exitOffline() {
        val e = ex(loc)
        if (isOn()) { lost = left(); alloc[loc] = 0.0; e.offline = true; pauseFor(Pause.OFFLINE) }
        else {
            e.offline = true; alloc[e.id] = 0.0
            val b = best(); if (b != null) { loc = b.id; mode = "auto" }
            setFlash(Tone.INFO, "${e.city} went offline.", if (b != null) "Auto picked ${b.city} instead." else "")
        }
    }

    fun connDrop() {
        if (conn != Conn.CONNECTED) { toast("Connect first: the drop happens to a live connection."); return }
        conn = Conn.RECONNECTING
        later(4000) { if (conn == Conn.RECONNECTING) { conn = Conn.CONNECTED; setFlash(Tone.OK, "Reconnected.", "Back through ${ex(loc).city}.") }; emit() }
    }

    fun ldFeedback(on: Boolean) {
        if (on) setFlash(Tone.OK, "Lockdown on.", if (isOn()) "If the VPN drops, nothing gets out." else "Nothing leaves your phone without the VPN.")
        else setFlash(Tone.INFO, "Lockdown off.", if (isOn()) "If the VPN drops, traffic goes direct." else "Traffic goes direct while the VPN is off.")
    }

    open fun startSync() {
        conn = Conn.IDLE; pause = null; home(); sheet = null; flash = null; fq.clear(); cancelConnect(); syncT = 0.0
    }
    open fun endSync() {
        syncT = null
        if (startConnect && !firstRun) requestConnect(loc)
    }

    /* ---- Android system VPN settings ----
       An app cannot switch Always-on VPN or "Block connections without VPN" itself. The app opens the
       system screen; what the user changed there is read when they come back (sysVpnReturned). */
    fun openSysVpn(purpose: String?) {
        sysPurpose = purpose; sysBefore = lockdown
        if (simulatesSystem) go("sysvpn") else hooks.openSystemVpnSettings()
    }
    /** Called when the user comes back from the (real or simulated) system screen. [now] is the Lockdown state read there. */
    fun sysVpnReturned(now: Boolean = lockdown) {
        val purpose = sysPurpose; val was = sysBefore; lockdown = now; sysPurpose = null
        if (purpose == "pay") {
            if (!now) { ldOffPay = true; conn = Conn.IDLE; pause = null; stack = mutableListOf("add"); onPayLockdownOff(); return }
            toast("Lockdown is still on. Paying needs it off for a moment.")
            return
        }
        if (now != was) {
            if (now) ldOffPay = false
            ldFeedback(now)
            toast(if (now) "Lockdown on. Nothing leaves without the VPN." else "Lockdown off.")
        } else if (purpose == "lockOn" && !now) toast("Lockdown is still off.")
    }
    /** The pay flow turned Lockdown off and disconnects (the real engine stops the tunnel). */
    open fun onPayLockdownOff() {}

    /** fresh() in the prototype: everything back to the first values, exits included. */
    open fun reset() {
        cancelConnect(); handler.removeCallbacksAndMessages(null)
        exits = initialExits().map { it.copy() }.toMutableList()
        conn = Conn.IDLE; pause = null; loc = "de"; mode = "auto"; alloc = mutableMapOf(); allocN = 0; free = false
        balance = 4800.0; added = 4800.0; balEp = 0
        autoRenew = true; lockdown = true; alwaysOn = true; startConnect = true; topup = false; warm = true; warmEnd = 180.0; popover = false
        icons = "node"; arIntro = false; askSwitch = true; arCount = 0; arPast4 = false; arDecl = -1
        t = 0.0; connSec = 0.0; firstRun = true
        stack = mutableListOf(); stab = "general"; sheet = null; offConfirm = false; details = false; simple = true
        flash = null; fq = mutableListOf(); ldOffPay = false; pay = null; offered = false; better = null
        sort = "fast"; q = ""; openRow = null; fullMsg = null; lost = 0.0; retryAt = 0.0
        nextCheck = 40.0; checking = false; swapAnim = false; failRenew = false; nextFull = false
        allocAt = mutableMapOf(); notes = mutableListOf(); fired = mutableMapOf(); recent = mutableListOf(); ptab = "best"; pall = false; relay = 2
        qsTile = false; unread = 0; gate = false; syncT = null; fail = null; update = null; askedSoon = -1
        startClock()
    }

    /* ---- actions ---- */
    open fun act(a: String, d: Map<String, String> = emptyMap()) {
        val keepFlash = a in setOf("qual", "details", "sort", "row", "tab", "nclose", "q", "ptab", "pall", "moreMsgs", "balance", "tip")
        if (!keepFlash && !a.startsWith("ev") && a !in setOf("dismiss", "dismissMsg", "arOn", "lockOn", "ldNot", "preset", "speed", "skip", "tgl", "bal", "sess", "mode", "theme", "codes", "rules")) {
            flash = null; fq.clear()
        }
        val v = d["v"] ?: ""
        when (a) {
            "connect" -> requestConnect(loc)
            "cancel" -> { cancelConnect(); conn = Conn.IDLE; onCancel() }
            "disconnect" -> { disconnect(); return }
            "settings" -> { go("settings"); stab = "general" }
            "usage" -> { sheet = null; go("settings"); stab = "usage" }
            "back" -> if (page() == "sysvpn") { back(); sysVpnReturned() } else back()
            "icons" -> icons = v
            "arReset" -> { arIntro = false; sheet = null; toast("Auto-renew intro will show on the next Connect.") }
            "traffic" -> go("traffic")
            "lockTap" -> openSysVpn(null)
            "details" -> details = !details
            "sort" -> sort = v
            "row" -> openRow = if (openRow == d["id"]) null else d["id"]
            "auto" -> {
                val b = best(); mode = "auto"
                if (b != null) { if (isOn() || conn == Conn.PAUSED) requestConnect(b.id, true) else { loc = b.id; home() } }
                emit(); return
            }
            "renew" -> { sheet = null; renewNow(); return }
            "bestAvail" -> { val b = best(loc); if (b != null) { mode = "auto"; requestConnect(b.id) }; emit(); return }
            "switchBetter" -> { mode = "manual"; better?.let { requestConnect(it) }; return }
            "notNow" -> better = null
            "dismiss" -> flash = fq.removeFirstOrNull()
            "dismissMsg" -> {
                val k = d["k"] ?: ""
                if (flash?.lead == k) flash = fq.removeFirstOrNull()
                else if (k == "Please fund your VPN.") topup = false
                else fq = fq.filter { it.lead != k }.toMutableList()
            }
            "arOn" -> { autoRenew = true; flash = null; fq.clear(); toast("Auto-renew is on for all exits.") }
            "offAsk" -> offConfirm = true
            "offCancel" -> offConfirm = false
            "offDo" -> { conn = Conn.IDLE; pause = null; offConfirm = false; onTurnOff() }
            "payOut" -> go("payout")
            "payGo" -> openSysVpn("pay")
            "addData" -> { sheet = null; if (conn == Conn.PAUSED && pause == Pause.BALANCE && lockdown) go("payout") else go("add") }
            "funding" -> { hooks.openUrl(fundUrl()); if (pay == null) pay = t + 120 }
            "copy" -> { copiedAt = now(); hooks.copy("VPN address", address()); toast("Address copied") }
            "share" -> hooks.share(address())
            "explorer" -> explorerUrl().takeIf { it.isNotEmpty() }?.let { hooks.openUrl(it) }
            "lockOn" -> openSysVpn("lockOn")
            "ldNot" -> ldOffPay = false
            "tab" -> stab = v
            "tog" -> { toggle(d["k"] ?: ""); if (d["k"] == "autoRenew" && autoRenew) offered = true }
            "sheetNo" -> { if (sheet?.check == true) arDecl = allocN; sheet = null }
            "gateFund" -> { hooks.openUrl(fundUrl()); if (pay == null) pay = t + 120 }
            "gateRestart" -> { balance = 0.0; added = 0.0; pay = null; home(); sheet = null; flash = null; fq.clear(); conn = Conn.IDLE; syncT = null; gate = true; fail = null }
            "togHide" -> popover = !popover
            "togAR" -> { autoRenew = !autoRenew; if (autoRenew) offered = true }
            "togP4" -> if (autoRenew) arPast4 = !arPast4
            "arCheckGo" -> { arCount = 0; sheet = null; autoRenewNow() }
            "arCheckNo" -> { arDecl = allocN; sheet = null }
            "arPastOn" -> { arPast4 = true; sheet = null; autoRenewNow(); toast("Auto-renew keeps going without asking.") }
            "sheetGo" -> {
                val sh = sheet ?: return; val id = sh.id
                if (sh.dont) askSwitch = false; if (sh.intro) arIntro = true; sheet = null
                if (sh.renew) { renewNow(); emit(); return }
                doConnect(id); return
            }
            "arOffGo" -> { val id = sheet?.id ?: return; autoRenew = false; arIntro = true; sheet = null; doConnect(id); toast("Auto-renew is off. We'll ask before setting data aside."); return }
            "dont" -> sheet?.let { it.dont = !it.dont }
            "arOnGo" -> {
                val sh = sheet ?: return; val id = sh.id
                autoRenew = true; arIntro = true; offered = true; sheet = null
                if (sh.renew) { renewNow(); emit(); return }
                doConnect(id); return
            }
            "stay" -> { sheet = null; home() }
            "logsExport" -> hooks.exportLogs()
            "logsUpload" -> toast("Logs sent to support.")
            "nclose" -> {}
            "pick" -> { go("pick"); ptab = "best"; pall = false; q = "" }
            "pick2" -> { mode = "manual"; requestConnect(d["id"] ?: return, true); return }
            "ptab" -> ptab = v
            "pall" -> pall = true
            "q" -> q = v
            "view" -> { simple = !simple; details = !simple }
            "qsAsk" -> hooks.requestTile()
            "qsAdd" -> { qsTile = true; toast("Tile added to Quick Settings.") }
            "qsToggle" -> when (conn) {
                Conn.IDLE -> requestConnect(loc)
                Conn.CONNECTING -> { cancelConnect(); conn = Conn.IDLE; onCancel() }
                else -> disconnect()
            }
            "balance" -> sheet = Sheet("balance")
            "sysAO" -> { alwaysOn = !alwaysOn; if (!alwaysOn) lockdown = false }
            "sysBlock" -> if (alwaysOn) lockdown = !lockdown
            "fundScreen" -> { go("add"); sheet = null }
            "restart" -> { fail = null; startSync() }
            "failRetry" -> { fail = null; startSync() }
            "copyCode" -> { fail?.let { hooks.copy("Failure code", it.code) }; toast("Failure code copied") }
        }
        emit()
    }

    /** Hooks for a real backend: the user cancelled a connect, or turned the VPN off while paused. */
    open fun onCancel() {}
    open fun onTurnOff() {}
    open fun address(): String = ""
    /** The VPN address on a block explorer; "" = none. */
    open fun explorerUrl(): String = ""
    /* Extension points for a real backend (defaults: the prototype's simulation). */
    open fun syncLen(): Double = SYNC_LEN
    /** Progress of "Discovering the network", 0..1. */
    open fun syncProgress(): Float = ((syncT ?: 0.0) / syncLen()).toFloat().coerceIn(0f, 1f)
    /** Text under the sync animation; null = the prototype's timed steps. */
    open fun syncStep(): String? = null
    /** Text of the connect stage; null = the prototype's four timed steps. */
    open fun connectStep(): String? = null
    /** The short id after the country name. */
    open fun exitTag(e: Exit): String = xid(e.id)
    open fun serverLoad(e: Exit): Int? = null
    /** What the node needs: token, what it pays for, amount. */
    open fun fundingNeeds(): List<Triple<String, String, String>> = emptyList()
    open fun fundUrl(): String = ""

    protected fun toggle(k: String) {
        when (k) {
            "autoRenew" -> autoRenew = !autoRenew
            "topup" -> topup = !topup
            "warm" -> warm = !warm
            "popover" -> popover = !popover
            "lockdown" -> lockdown = !lockdown
            "startConnect" -> startConnect = !startConnect
            "nextFull" -> nextFull = !nextFull
            "failRenew" -> failRenew = !failRenew
        }
    }

    /* ---- clock ---- */
    private var lastTick = 0L
    private val tick = object : Runnable {
        override fun run() {
            val n = now(); val dt = (n - lastTick) / 1000.0 * speed; lastTick = n
            syncT?.let { st -> syncT = st + dt; if (syncT!! >= syncLen()) endSync() }
            t += dt; step(dt); emit()
            handler.postDelayed(this, 250)
        }
    }
    private val relayTick = object : Runnable {
        override fun run() {
            if (conn == Conn.CONNECTED || conn == Conn.CONNECTING || conn == Conn.RECONNECTING) relay = (relay + 1 + (0..3).random()) % 6
            handler.postDelayed(this, 2600)
        }
    }
    /** Stops the clock (the app switches engines). */
    open fun stop() { handler.removeCallbacksAndMessages(null) }
    fun startClock() {
        handler.removeCallbacks(tick); handler.removeCallbacks(relayTick)
        lastTick = now(); handler.postDelayed(tick, 250); handler.postDelayed(relayTick, 2600)
    }

    /** First launch: the node has no funds yet. */
    open fun start() {
        reset()
        gate = true; balance = 0.0; added = 0.0
        emit()
    }
}
