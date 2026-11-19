package org.gnosisvpn.stub.ui

import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Pure view logic of the main screen, ported from the prototype's ui.js: the state messages
 * (moment/moments/simpleLine), the screen codes (screenCode) and the ongoing notification.
 * "{retry}" in a text is replaced live with the retry countdown.
 */

private val TONE_ICON = mapOf(Tone.OK to "check_circle", Tone.WARN to "warning", Tone.BAD to "pause_circle", Tone.INFO to "info", Tone.LAV to "system_update")

fun Engine.statusTxt() = when (conn) {
    Conn.IDLE -> "Not connected"; Conn.CONNECTING -> "Connecting"; Conn.CONNECTED -> "Connected"
    Conn.RECONNECTING -> "Reconnecting"; Conn.PAUSED -> "Paused"
}

fun Engine.retryText() = "0:" + p2(max(0.0, retryAt - t))

fun Engine.moment(): Msg? {
    val e = ex(loc); val a = left()
    if (conn == Conn.PAUSED) when (pause) {
        Pause.NODATA -> return Msg(Tone.BAD, "pause_circle", "Internet paused.", "No data left at ${e.city}. Your traffic stays private while paused.")
        Pause.OFFLINE -> return Msg(Tone.BAD, "cloud_off", "${e.city} went offline.", "The ${lost.roundToInt()} MB left there is lost, even if ${e.city} comes back.")
        Pause.BALANCE -> return Msg(Tone.BAD, "pause_circle", "Internet paused.",
            if (lockdown) "No data at ${e.city} and none in your balance. Paying needs the VPN and Lockdown off for a moment."
            else "No data at ${e.city} and none in your balance. Add data to continue.")
        Pause.RENEWFAIL -> return Msg(Tone.BAD, "sync_problem", "Paused: payment failed.",
            "${e.city} holds your traffic until a payment goes through, so nothing leaks. " +
                (if (retryCountdown) "Trying again in {retry}." else "Gnosis VPN keeps retrying.") +
                (if (balanceLow()) " Your balance is low." else ""),
            if (balanceLow()) listOf("Add funds" to "addData") else null)
        null -> {}
    }
    if (conn == Conn.RECONNECTING) return if (lockdown)
        Msg(Tone.LAV, "lock", "Lockdown on.", "Internet is blocked while Gnosis VPN reconnects to ${e.city}.")
    else Msg(Tone.WARN, "warning", "Reconnecting.", "Lockdown is off, so traffic isn’t protected until the connection is back.")
    if (conn == Conn.CONNECTING) return Msg(Tone.INFO, "info", "Connecting to ${e.city}.",
        if (!metered) "Opening a route through the mixnet." else if (a > 0) "Uses the ${a.roundToInt()} MB left here." else if (free) "Setting aside your free ${chunkTxt()} at ${e.city}." else "Setting aside ${chunkTxt()} at ${e.city}.")
    if (conn == Conn.CONNECTED && awaitingPayment) return Msg(if (paymentFailed) Tone.WARN else Tone.INFO, "hourglass_top", "Waiting for ${e.city} to see your payment.",
        (if (paymentFailed) "The last payment failed; Gnosis VPN retries it automatically."
         else "On a new connection this takes up to about 3 minutes while Gnosis Chain confirms it.") +
            " Traffic resumes by itself." + (if (balanceLow()) " Your balance is low." else ""),
        if (balanceLow()) listOf("Add funds" to "addData") else null)
    flash?.let { f -> return Msg(f.tone, TONE_ICON[f.tone] ?: "info", f.lead, f.text, f.btns.ifEmpty { null }) }
    if (conn == Conn.IDLE && ldOffPay && pay != null) return Msg(Tone.INFO, "hourglass_top", "Waiting for payment…", "Usually about 2 minutes. Connect becomes available once it arrives.")
    if (conn == Conn.CONNECTED) {
        if (a <= LOW && !autoRenew) return Msg(Tone.WARN, "warning", "${a.roundToInt()} MB left here.", "About ${dur(minsLeft())} at this rate.", listOf("Renew here · ${chunkTxt()}" to "renew"))
        if (a <= LOW && short()) return Msg(Tone.WARN, "warning", "${a.roundToInt()} MB left here.", "Your balance is empty, so the internet pauses when this runs out.", listOf("Add data" to "addData"))
        if (balance > 0 && balance <= chunk) return Msg(Tone.WARN, "warning", "Balance running low.", "${mb(balance)} left, enough for one more renewal.", listOf("Add data" to "addData"))
        better?.let { bid -> val b = ex(bid); return Msg(Tone.INFO, "info", "Better exit now: ${b.city}.", "${b.ping} ms, faster than ${e.city}.", listOf((if (left(b.id) > 0) "Switch" else "Switch · ${chunkTxt()}") to "switchBetter", "Not now" to "notNow")) }
        return null
    }
    if (conn == Conn.IDLE) {
        if (checking) return Msg(Tone.INFO, "radar", "Checking exits…", "Auto keeps the best location up to date until you connect.")
        if (short() && !(a > 0)) return Msg(Tone.WARN, "warning", "Balance empty.", "Add data to connect again.", listOf("Add data" to "addData"))
        if (mode == "manual") return Msg(Tone.INFO, "push_pin", "Selected Location.", "Your pick stays until you change it.")
    }
    return null
}

/** All messages to show, most severe first (moments() in the prototype). */
fun Engine.moments(): List<Msg> {
    val out = mutableListOf<Msg>(); val seen = HashSet<String>()
    fun add(m: Msg?, own: Boolean) { if (m != null && seen.add(m.lead)) out.add(m.copy(own = own)) }
    if (topup) add(Msg(Tone.INFO, "account_balance_wallet", "Please fund your VPN.", "", listOf("Fund" to "fundScreen")), true)
    add(moment(), flash != null)
    if (flash != null) { val keep = flash; flash = null; add(moment(), false); flash = keep }
    for (x in fq) add(Msg(x.tone, TONE_ICON[x.tone] ?: "info", x.lead, x.text, x.btns.ifEmpty { null }), true)
    if (out.isNotEmpty() && flash != null) out[0] = out[0].copy(own = out[0].lead == flash!!.lead || out[0].lead == "Please fund your VPN.")
    val rank = mapOf(Tone.BAD to 0, Tone.WARN to 1)
    return out.withIndex().sortedWith(compareBy({ rank[it.value.tone] ?: 2 }, { it.index })).map { it.value }
}

/** Shorter copy for the simple view, as in the demo. */
fun Engine.simpleLine(m: Msg): Msg {
    var lead = m.lead; var text = m.text; var btns = m.btns
    fun first(t: String) = t.split(Regex("(?<=\\.)\\s"))[0]
    if (conn == Conn.PAUSED && lead != flash?.lead) {
        text = when (pause) { Pause.NODATA -> "Your traffic stays private."; Pause.OFFLINE -> "The data left there is lost."; Pause.BALANCE -> "Your balance is empty."; Pause.RENEWFAIL -> if (balanceLow()) "Your balance is low." else "Gnosis VPN keeps retrying."; null -> text }
        if (pause != Pause.RENEWFAIL) btns = null
    } else if (conn == Conn.CONNECTED && left() <= LOW && !autoRenew && lead.contains("MB left")) { lead = "Data running low."; text = "About ${dur(minsLeft())} left." }
    else if (lead.startsWith("Better exit now")) { lead = "${exOrNull(better)?.city} is faster now."; text = "" }
    else text = if (m.tone == Tone.OK && !lead.startsWith("Lockdown")) "" else first(text)   // good news needs no second line; Lockdown's always says what it means
    return m.copy(lead = lead, text = text, btns = btns?.take(1))
}

/** The prototype's screen codes (S00–S18, F1–F5, L1, P1–P4, I-1, I0, E1), for Demo controls and tests. */
fun Engine.screenCode(): Pair<String, String> {
    fail?.let { return "E1" to "Failure page" }
    if (syncT != null) return "I0" to "Discovering the network"
    val pg = page()
    if (pg == "sysvpn") return "SYS" to "Android VPN settings"
    val sh = sheet
    if (sh != null && !(sh.type == "quota" && pg != null)) return when (sh.type) {
        "quota" -> if (sh.check) "S09q" to "Auto-renew check-in after 4 renewals" else if (sh.intro) "S04i" to "Auto-renew explained, once per install"
            else if (sh.renew) "S08q" to "${chunkTxt()} used up, ask to renew" else "S04q" to "Data use, Auto-renew off"
        "switch" -> "S04" to "Switch, first time"
        "balance" -> "—" to "Balance sheet"
        else -> "S15" to "Not enough data to switch"
    }
    when (pg) {
        "pick" -> return if (fullMsg != null) "S11" to "Exit just filled up" else "S03s" to "Change exit"
        "settings" -> return if (stab == "general") "S19n" to "Settings, General" else "S06n" to "Settings, Usage"
        "add" -> return "S18" to "Add data"
        "payout" -> return "S18a" to "Pay outside the VPN"
        "traffic" -> return "—" to "Live traffic"
        "demo" -> return "—" to "Demo controls"
    }
    val a = left(); val f = flash?.lead ?: ""
    if (gate) return if (pay != null) "I-1w" to "Fund first, waiting for payment" else "I-1" to "Fund first"
    when (conn) {
        Conn.CONNECTING -> return "S00b" to "Connecting"
        Conn.RECONNECTING -> return if (lockdown) "L1" to "Lockdown, reconnecting" else "L1*" to "Reconnecting, Lockdown off"
        Conn.PAUSED -> return if (offConfirm) "S08c" to "Turning the VPN off" else when (pause) {
            Pause.NODATA -> "S08" to "Paused, no data left"; Pause.OFFLINE -> "S10" to "Paused, exit went offline"
            Pause.BALANCE -> "S14" to "Paused, balance empty"; else -> "S16" to "Paused, renewal failed"
        }
        Conn.CONNECTED -> {
            if (f.startsWith("Payment")) return if (autoRenew) "P1" to "Paid, connected, Auto-renew on" else "P2" to "Paid, connected, Auto-renew off"
            if (f.startsWith("Renewed auto")) return "S05" to "Renewed automatically"
            if (f.startsWith("Back online")) return "S09" to "Back online"
            if (ldOffPay) return "S18d" to "Connected, Lockdown still off"
            if (a <= LOW && !autoRenew) return "S07" to "Data low, Auto-renew off"
            if (balance > 0 && balance <= chunk) return "S13" to "Balance running low"
            return if (details && !simple) "S02" to "Connected, details open" else "S01" to "Connected"
        }
        Conn.IDLE -> {}
    }
    if (f.startsWith("Payment")) return if (ldOffPay) "S18e" to "Payment arrived, Lockdown off" else if (autoRenew) "P3" to "Paid, disconnected, Auto-renew on" else "P4" to "Paid, disconnected, Auto-renew off"
    if (ldOffPay && pay != null) return "S18c" to "Waiting for payment, Lockdown off"
    if (checking) return "F3" to "Interval passed: checking"
    if (f.contains("now the best")) return "F4 → F5" to "New best found"
    if (mode == "manual") return "F1" to "You picked an exit"
    return "F2" to "Auto: Current Best Location"
}

/** The ongoing VPN-service notification, or null while idle. */
fun Engine.ongoingNote(): Note? {
    val e = ex(loc)
    return when (conn) {
        Conn.IDLE -> null
        Conn.CONNECTING -> Note("ongoing", "Connecting to ${e.city}", "Opening channels to relays…", listOf("Cancel" to "cancel"))
        Conn.CONNECTED -> Note("ongoing", "Connected · ${e.city}", if (left() > 0) "${mb(left())} left" + (timeLeft()?.let { " · $it" } ?: "") else "Rotating relays", listOf("Disconnect" to "disconnect"))
        Conn.RECONNECTING -> Note("ongoing", "Reconnecting…", if (lockdown) "Lockdown on: internet blocked until the connection is back." else "Traffic isn’t protected until the connection is back.", listOf("Disconnect" to "disconnect"))
        Conn.PAUSED -> {
            val why = when (pause) { Pause.NODATA -> "No data left at ${e.city}."; Pause.OFFLINE -> "${e.city} went offline."; Pause.BALANCE -> "No data and none in your balance."; else -> "Payment failed; retrying." }
            Note("ongoing", "Internet paused", "$why Traffic stays blocked.", when (pause) {
                Pause.NODATA -> listOf("Renew · ${chunkTxt()}" to "renew"); Pause.OFFLINE -> listOf("Best available" to "bestAvail")
                Pause.BALANCE -> listOf("Add data" to "addData")
                else -> if (balanceLow()) listOf("Add funds" to "addData") else listOf("Disconnect" to "disconnect")
            })
        }
    }
}
