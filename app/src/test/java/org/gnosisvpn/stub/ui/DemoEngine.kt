package org.gnosisvpn.stub.ui

/*
 * The prototype's fake backend (~/mobile-UI src/shared/engine.js) for the unit tests only: its
 * exits, scenarios (Demo controls → Jump to) and simulated events. The app itself runs only the
 * Gnosis engine; this checks that the shared state machine keeps the prototype's rules.
 */

const val ADDRESS = "0x7a3f9c2e5b81d0c4e6a2b7d48d1c91e3"

fun baseExits() = listOf(
    Exit("nl", "Amsterdam", "Netherlands", ping = 451, rel = 2, spots = 6, slots = 10, pv = 0.97, fr = 3, measured = true, busy = "Low"),
    Exit("at", "Vienna", "Austria", ping = 560, rel = 3, spots = 4, slots = 10, pv = 0.95, fr = 4, measured = true, busy = "Low"),
    Exit("de", "Frankfurt", "Germany", ping = 612, rel = 3, spots = 8, slots = 10, pv = 0.96, fr = 4, measured = true, busy = "Medium"),
    Exit("pt", "Lisbon", "Portugal", ping = 690, rel = 2, spots = 5, slots = 10, pv = 0.93, fr = 2, measured = false, busy = "Low"),
    Exit("ch", "Zürich", "Switzerland", ping = 740, rel = 3, spots = 1, slots = 10, pv = 0.91, fr = 3, measured = true, busy = "High"),
    Exit("se", "Stockholm", "Sweden", ping = 820, rel = 1, spots = 7, slots = 10, pv = 0.84, fr = 2, measured = true, busy = "Low"),
    Exit("br", "São Paulo", "Brazil", ping = 1307, rel = 2, spots = 9, slots = 10, pv = 0.92, fr = 1, measured = true, busy = "Low"),
    Exit("gb", "London", "United Kingdom", ping = 380, rel = 3, spots = 0, full = true),
    Exit("pl", "Warsaw", "Poland", ping = 470, rel = 2, spots = 0, full = true),
    Exit("kr", "Seoul", "South Korea", ping = 950, rel = 2, spots = 0, full = true),
).onEach { e -> e.place = e.city; e.city = "${e.country} #${xid(e.id)}" }


class DemoEngine : Engine() {
    override val simulatesSystem = true
    override fun initialExits() = baseExits()
    override fun address() = ADDRESS
    override fun fundingNeeds() = listOf(Triple("wxHOPR", "Pays for your traffic", "234.52"), Triple("xDAI", "Pays network fees", "0.0126"))

    /* ---- presets (Demo controls → Jump to) ---- */
    fun preset(k: String) {
        val keepNotes = notes; val keepFired = fired
        val keep = listOf(speed, autoRenew, popover, topup, arPast4, lockdown, alwaysOn, startConnect, icons, simple, details, qsTile)
        val keepIntro = if (k == "s00") false else arIntro
        reset()
        speed = keep[0] as Int; autoRenew = keep[1] as Boolean; popover = keep[2] as Boolean; topup = keep[3] as Boolean
        arPast4 = keep[4] as Boolean; lockdown = keep[5] as Boolean; alwaysOn = keep[6] as Boolean; startConnect = keep[7] as Boolean
        icons = keep[8] as String; simple = keep[9] as Boolean; details = keep[10] as Boolean; qsTile = keep[11] as Boolean
        arIntro = keepIntro; notes = keepNotes; fired = keepFired
        fun on(id: String, a: Double, sec: Double = 754.0) { loc = id; alloc[id] = a; allocN++; conn = Conn.CONNECTED; connSec = sec; firstRun = false; free = false }
        when (k) {
            "s01" -> { on("nl", 560.0, 20.0); balance = 4800.0 }
            "arc" -> { on("nl", 30.4, 1900.0); balance = 4800.0; autoRenew = true; arPast4 = false; arCount = 4; sheet = Sheet("quota", "nl", check = true, until = t + 300) }
            "s07" -> { on("br", 126.0); autoRenew = false; balance = 3600.0 }
            "s08" -> { on("br", 0.0); autoRenew = false; balance = 3600.0; pauseFor(Pause.NODATA) }
            "s10" -> { on("br", 180.0); balance = 3600.0; exitOffline() }
            "s13" -> { on("nl", 410.0); balance = 600.0 }
            "s14" -> { on("br", 0.0); balance = 0.0; pauseFor(Pause.BALANCE) }
            "s18c" -> { firstRun = false; free = false; balance = 0.0; lockdown = false; ldOffPay = true; pay = t + 120; loc = "br"; mode = "manual" }
            "l1" -> { on("nl", 410.0); connDrop() }
        }
        warm = k == "s00"; warmEnd = t + 180
        if (conn != Conn.IDLE || k == "s18c") {
            recent = (listOf(Recent(loc, 0.0)) + listOf("ch", "de", "at", "pt").filter { it != loc }.mapIndexed { i, x -> Recent(x, -3600.0 * (i + 1)) }).take(6).toMutableList()
        }
        emit()
    }


    override fun act(a: String, d: Map<String, String>) {
        if (!a.startsWith("ev") && a !in setOf("preset", "speed", "skip", "tgl", "bal", "sess")) { flash = null; fq.clear() }
        val v = d["v"] ?: ""
        when (a) {
            "preset" -> { preset(v); return }
            "speed" -> speed = v.toInt()
            "skip" -> { repeat(300) { t += 1; step(1.0) }; t = Math.round(t).toDouble() }
            "sess" -> {
                if (!isOn()) { conn = Conn.CONNECTED; connSec = 0.0; firstRun = false; free = false; pause = null; home() }
                if (balance < 1200) balance = 4800.0
                alloc[loc] = v.toDouble(); sheet = null
                fired.keys.filter { Regex("^(u3|u6|a3|u2)(:|$)").containsMatchIn(it) }.forEach { fired.remove(it) }
                toast("$v MB left in this session at ${ex(loc).city}.")
            }
            "bal" -> { balance = v.toDouble(); if (balance > chunk) balEp++ }
            "evOffline" -> if (conn != Conn.CONNECTING) exitOffline()
            "evDrop" -> connDrop()
            "evTwo" -> { ldFeedback(true); update = UPD_V; setFlash(Tone.LAV, "Update available.", "Gnosis VPN $UPD_V is ready.", listOf("Restart to update" to "updNow", "Later" to "updLater")) }
            "evUpdate" -> if (conn != Conn.CONNECTING) {
                update = UPD_V; fire("upd:$UPD_V:${t.toInt()}", nUpd())
                setFlash(Tone.LAV, "Update available.", "Gnosis VPN $UPD_V is ready.", listOf("Restart to update" to "updNow", "Later" to "updLater"))
            }
            "updLater" -> flash = fq.removeFirstOrNull()
            "updNow" -> {
                val was = isOn(); update = null; flash = null; fq.clear()
                if (was) {
                    conn = Conn.RECONNECTING
                    later(2600) { if (conn == Conn.RECONNECTING) { conn = Conn.CONNECTED; setFlash(Tone.OK, "Updated.", "You're on Gnosis VPN $UPD_V and connected again.") }; emit() }
                } else setFlash(Tone.OK, "Updated.", "You're on Gnosis VPN $UPD_V.")
            }
            "evPay" -> { pay = t; paymentArrived() }
            "tgl" -> toggle(d["k"] ?: "")
            "failShow" -> { fail = Failure("Can’t connect to Blokli", "BLINDTEXT"); home(); sheet = null }
            "syncSkip" -> endSync()
            "reset" -> { val sp = speed; reset(); speed = sp }
            "clearLog" -> { notes = mutableListOf(); fired = mutableMapOf(); unread = 0 }
            "gatePaid" -> { pay = t; paymentArrived() }
            "go" -> go(v)
            else -> { super.act(a, d); return }
        }
        emit()
    }
}
