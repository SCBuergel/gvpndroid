package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/**
 * The engine against the prototype (~/mobile-UI src/shared/engine.js): its scenarios reach the
 * screens the prototype's deep links show, and the rules (renewals, notifications, the pay flow)
 * behave the same. Timers (Handler) are inert in unit tests, so time is advanced with step().
 */
class EngineTest {
    private fun engine() = DemoEngine().also { it.reset() }

    private fun Engine.run(seconds: Int) { repeat(seconds) { t += 1; step(1.0) } }

    @Test fun presetsReachThePrototypeScreens() {
        // s07 starts at 126 MB, just above LOW: S01 until it drops below 120 MB (as in the prototype)
        val want = mapOf("s00" to "F2", "s01" to "S01", "s07" to "S01", "s08" to "S08q", "s10" to "S10", "s13" to "S13",
            "s14" to "S14", "s18c" to "S18c", "l1" to "L1", "arc" to "S09q")
        for ((k, code) in want) {
            val e = engine(); e.preset(k)
            assertEquals("preset $k", code, e.screenCode().first)
        }
        val e = engine(); e.preset("s07"); e.run(80)
        assertEquals("S07", e.screenCode().first)
    }

    @Test fun firstLaunchIsTheFundingGate() {
        val e = DemoEngine(); e.start()
        assertEquals("I-1", e.screenCode().first)
        e.act("gateFund"); assertEquals("I-1w", e.screenCode().first)
        e.act("gatePaid")
        assertEquals("I0", e.screenCode().first)
        assertEquals(PAY, e.balance, 0.0)
    }

    @Test fun exitNamesAndGradesMatchThePrototype() {
        val e = engine()
        assertEquals("7f41", xid("br")); assertEquals("9651", xid("de")); assertEquals("734a", xid("at")); assertEquals("8b44", xid("ch"))
        assertEquals("Brazil #7f41", e.ex("br").city)
        assertEquals("de", e.best()?.id)
        assertEquals("Excellent", e.grade(e.ex("de")).label)
        assertEquals("Good", e.grade(e.ex("nl")).label)
        assertEquals("Not measured yet", e.grade(e.ex("pt")).label)
        assertEquals("Weak route", e.grade(e.ex("se")).label)
        assertEquals("hidden", e.grade(e.ex("gb")).k)
    }

    @Test fun autoRenewSetsAsideTheNext600MbQuietly() {
        val e = engine(); e.preset("s01")   // Netherlands, 560 MB left, 4.8 GB balance
        e.alloc["nl"] = 40.0
        e.run(200)
        assertTrue(e.left() > 500)
        assertEquals(4800.0 - COST, e.balance, 0.0)
        assertEquals("Renewed automatically.", e.flash?.lead)
        assertTrue(e.notes.any { it.rule == "u2" && it.silent })
        assertFalse(e.notes.any { !it.silent })
    }

    @Test fun autoRenewOffWarnsThenAsksThenPauses() {
        val e = engine(); e.preset("s07")   // Brazil, 126 MB, Auto-renew off
        e.run(100)
        assertTrue("U3 fired", e.notes.any { it.rule == "u3" })
        e.alloc["br"] = 20.0
        e.run(1)
        assertTrue("U6 fired", e.notes.any { it.rule == "u6" })
        assertTrue("asks to renew before the internet pauses", e.sheet?.soon == true)
        assertEquals("S08q", e.screenCode().first)
        e.act("sheetNo")
        e.run(300)
        assertEquals(Conn.PAUSED, e.conn); assertEquals(Pause.NODATA, e.pause)
        assertTrue("A3 fired", e.notes.any { it.rule == "a3" })
        e.act("sheetNo")
        assertEquals("S08", e.screenCode().first)
        e.act("renew")
        assertEquals(Conn.RECONNECTING, e.conn)
    }

    @Test fun fourRenewalsCheckIn() {
        val e = engine(); e.preset("arc")
        assertNotNull(e.sheet?.takeIf { it.check })
        e.act("arCheckGo")
        assertNull(e.sheet)
        assertEquals(1, e.arCount)
        assertTrue(e.left() > COST)
    }

    @Test fun payingWithLockdownGoesOutsideTheVpn() {
        val e = engine(); e.preset("s14")   // paused, balance empty, Lockdown on
        assertEquals("S14", e.screenCode().first)
        e.act("payOut"); assertEquals("S18a", e.screenCode().first)
        e.act("payGo"); assertEquals("sysvpn", e.page())
        e.act("back")   // came back without turning blocking off
        assertEquals("payout", e.page()); assertTrue(e.lockdown)
        e.act("payGo"); e.act("sysBlock"); assertFalse(e.lockdown)
        e.act("back")
        assertTrue(e.ldOffPay); assertEquals(Conn.IDLE, e.conn); assertEquals("add", e.page())
        e.act("funding"); assertNotNull(e.pay)
        e.run(130)
        assertTrue("N7 with the Lockdown reminder", e.notes.any { it.rule == "n8" && it.code == "N7" })
        assertEquals(PAY, e.balance, 0.0)
    }

    @Test fun turningOffWhilePausedAsksFirst() {
        val e = engine(); e.preset("s08"); e.act("sheetNo")
        e.act("offAsk"); assertEquals("S08c", e.screenCode().first)
        e.act("offDo"); assertEquals(Conn.IDLE, e.conn)
    }

    @Test fun messagesAreSortedBySeverity() {
        val e = engine(); e.preset("s13")   // balance 600: "Balance running low"
        e.act("evUpdate")
        val m = e.moments()
        assertEquals("Balance running low.", m.first().lead)
        assertTrue(m.any { it.lead == "Update available." })
    }

    @Test fun ongoingNotificationFollowsTheConnection() {
        val e = engine()
        assertNull(e.ongoingNote())
        e.preset("s01"); assertEquals("Disconnect", e.ongoingNote()?.acts?.first()?.first)
        e.preset("s14"); assertEquals("Add data", e.ongoingNote()?.acts?.first()?.first)
    }
}

/** A backend that reports whatever the test sets: what the Gnosis core would say. */
class FakeTunnel : org.gnosisvpn.stub.Tunnel {
    override var state = org.gnosisvpn.stub.Tunnel.State.DISCONNECTED
    override var locations: List<org.gnosisvpn.stub.Tunnel.Location> = emptyList()
    override var fundingRequest: org.gnosisvpn.stub.Tunnel.FundingRequest? = null
    override var phase: String? = null
    override var startup: org.gnosisvpn.stub.Tunnel.Startup? = null
    override var failure: String? = null
    override var session: org.gnosisvpn.stub.Tunnel.Session? = null
    override var chunkBytes: Long? = 660_160_512
    override var wallet: org.gnosisvpn.stub.Tunnel.Wallet? = null
    var lastErrorText: String? = null
    override val lastError: String? get() = lastErrorText
    var counters: org.gnosisvpn.stub.Tunnel.Stats? = null
    val selected = mutableListOf<String>()
    override fun connect() { state = org.gnosisvpn.stub.Tunnel.State.CONNECTING }
    override fun disconnect() { state = org.gnosisvpn.stub.Tunnel.State.DISCONNECTED }
    override fun stats() = counters
    override fun addListener(listener: org.gnosisvpn.stub.Tunnel.Listener) {}
    override fun removeListener(listener: org.gnosisvpn.stub.Tunnel.Listener) {}
    override fun selectLocation(location: org.gnosisvpn.stub.Tunnel.Location) { selected += location.id }
}

class GnosisEngineTest {
    private fun ger(ping: Int? = 380, free: Int = 4) = org.gnosisvpn.stub.Tunnel.Location("0xe5cE", "GVPN-GER-1", "DE",
        org.gnosisvpn.stub.Tunnel.ExitHealth("DE", pingMs = ping, slotsTotal = 10, slotsFree = free, pathValue = 0.95, load = 0.3))
    private fun uk() = org.gnosisvpn.stub.Tunnel.Location("0xE7FD", "Bonnie Prince Charlie", "Edinburgh, UK",
        org.gnosisvpn.stub.Tunnel.ExitHealth("GB", pingMs = 900, slotsTotal = 10, slotsFree = 8, pathValue = 0.92))
    private fun wallet(wx: String, lvl: org.gnosisvpn.stub.Tunnel.Level = org.gnosisvpn.stub.Tunnel.Level.GOOD) =
        org.gnosisvpn.stub.Tunnel.Wallet("0xf9f8", java.math.BigDecimal(wx), java.math.BigDecimal("0.004"), lvl, org.gnosisvpn.stub.Tunnel.Level.GOOD,
            dataBytes = java.math.BigDecimal(wx).divide(java.math.BigDecimal("0.000000009"), java.math.MathContext.DECIMAL64).toLong())

    private class Hooks : EngineHooks {
        var connects = 0; var disconnects = 0; val notes = mutableListOf<Note>()
        override fun connectVpn() { connects++ }
        override fun disconnectVpn() { disconnects++ }
        override fun notify(note: Note) { notes += note }
    }

    private fun setup(): Triple<FakeTunnel, GnosisEngine, Hooks> {
        val tn = FakeTunnel(); val h = Hooks()
        val e = GnosisEngine({ tn }); e.hooks = h; e.start()
        return Triple(tn, e, h)
    }
    private fun GnosisEngine.tick(n: Int = 1) = repeat(n) { t += 1; step(1.0) }

    /** The core reports percent_of_best (IPC v5): bars as gnosis_vpn-app's routeGrade, cut at 90, 60 and 25. */
    @Test fun gradesFollowThePercentOfTheBestExit() {
        val (tn, e, _) = setup()
        tn.phase = "running"
        fun exit(id: String, pct: Int?) = org.gnosisvpn.stub.Tunnel.Location(id, "Exit $id", "DE",
            org.gnosisvpn.stub.Tunnel.ExitHealth("DE", pingMs = 400, slotsTotal = 10, slotsFree = 5, pathValue = pct?.let { it / 100.0 }))
        tn.locations = listOf(exit("0xa", 100), exit("0xb", 89), exit("0xc", 59), exit("0xd", 24), exit("0xe", null))
        e.tick()
        assertEquals(listOf(4, 3, 2, 1, 0), listOf("0xa", "0xb", "0xc", "0xd", "0xe").map { e.grade(e.ex(it)).n })
        assertEquals("Not measured yet", e.grade(e.ex("0xe")).label)
        assertEquals("0xa", e.best()?.id)
    }

    @Test fun fundingGateThenStartupThenRunning() {
        val (tn, e, h) = setup()
        tn.fundingRequest = org.gnosisvpn.stub.Tunnel.FundingRequest("0xf9f8a1C7aDBb89460F0249A9DC5A9327068EF072", "225.35 wxHOPR + 0.005 xDAI"); tn.phase = "preparing_safe"
        e.tick()
        assertEquals("I-1", e.screenCode().first)
        assertEquals("0xf9f8a1C7aDBb89460F0249A9DC5A9327068EF072", e.address())
        assertEquals("225.35", e.fundingNeeds()[0].third); assertEquals("0.005", e.fundingNeeds()[1].third)
        assertEquals("https://gnosisscan.io/address/0xf9f8a1C7aDBb89460F0249A9DC5A9327068EF072", e.explorerUrl())
        assertEquals("225.35 wxHOPR and 0.005 xDAI", e.fundingAmounts())   // shown under the address
        e.act("gateFund"); assertEquals("I-1w", e.screenCode().first)
        tn.fundingRequest = null; tn.phase = "warmup"; tn.startup = org.gnosisvpn.stub.Tunnel.Startup(5, 19, "Connecting to Gnosis Chain")
        e.tick()
        assertEquals("I0", e.screenCode().first); assertEquals("Connecting to Gnosis Chain", e.syncStep())
        assertTrue(e.syncProgress() in 0.2f..0.4f)
        assertTrue("payment noted", h.notes.any { it.rule == "n8" })
        tn.phase = "running"; tn.startup = null; tn.locations = listOf(ger(), uk())
        e.tick()
        assertEquals("F2", e.screenCode().first)
        assertEquals("Germany", e.ex(e.loc).country)   // best: lower latency, better path
        assertEquals("e5ce", e.exitTag(e.ex(e.loc)))
    }

    @Test fun connectedSessionShowsRealData() {
        val (tn, e, h) = setup()
        tn.phase = "running"; tn.locations = listOf(ger(), uk()); tn.wallet = wallet("54")   // 6 GB at the configured price
        e.tick()
        assertEquals("6 GB", e.balanceText())
        e.act("connect")
        assertEquals(1, h.connects); assertEquals(listOf("0xe5cE"), tn.selected)
        tn.state = T.State.CONNECTING
        tn.session = T.Session("0xe5cE", 1, step = 4, steps = 10, stepName = "Registering with the exit")
        e.tick()
        assertEquals(Conn.CONNECTING, e.conn); assertEquals("Registering with the exit", e.connectStep()); assertEquals(0.5f, e.connectFrac()!!, 0.01f)
        tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, pingMs = 350, chunkBytes = 660_160_512, bytesLeft = 500_000_000, bytesBought = 660_160_512, payment = T.Payment.FUNDED, emptyInSec = 1800)
        e.tick()
        assertEquals(Conn.CONNECTED, e.conn); assertEquals("S01", e.screenCode().first)
        assertEquals(500.0, e.left(), 0.1); assertEquals(350, e.ex(e.loc).ping); assertEquals(30, e.minsLeft())
        assertEquals("about 30 min", e.timeLeft())
        assertEquals("500 MB left · about 30 min", e.ongoingNote()!!.body)
        assertEquals("660 MB", e.chunkTxt())
        // the core buys the next chunk: "Renewed automatically"
        tn.session = tn.session!!.copy(bytesLeft = 1_100_000_000, bytesBought = 1_320_321_024)
        e.tick()
        assertEquals("S05", e.screenCode().first)
        assertFalse("renewals are silent", h.notes.any { it.rule == "u2" })
        // disconnect from the app
        e.act("disconnect"); assertEquals(1, h.disconnects)
        tn.state = T.State.DISCONNECTED; tn.session = null; e.tick()
        assertEquals(Conn.IDLE, e.conn)
    }

    @Test fun balanceLevelsAndPayments() {
        val (tn, e, h) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); tn.wallet = wallet("5", T.Level.LOW)
        e.tick()
        assertTrue("balance low", h.notes.any { it.rule == "u4" })
        assertTrue(e.balance <= e.chunk)
        tn.wallet = wallet("0", T.Level.EMPTY); e.tick()
        assertTrue("balance empty", h.notes.any { it.rule == "u5" })
        assertEquals("Add data", e.moments().first().btns?.first()?.first)
        tn.wallet = wallet("27"); e.tick()
        val paid = h.notes.last { it.rule == "n8" }
        assertTrue(paid.body, paid.body.startsWith("3 GB added"))
        assertEquals("Payment received.", e.flash?.lead)
        // Usage: what the wallet holds, per token; nothing is missing, so no amount to send
        assertNull(e.fundingAmounts())
        assertTrue(e.fundingNeeds()[0].second.contains("you have 27"))
    }

    @Test fun timeLeftIsTheCoresEstimateOnly() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); tn.wallet = wallet("54"); tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, bytesLeft = 500_000_000, bytesBought = 660_160_512, payment = T.Payment.FUNDED)
        e.tick()
        assertNull("no rate, no estimate", e.timeLeft())
        assertEquals("500 MB left", e.ongoingNote()!!.body)
        tn.session = tn.session!!.copy(emptyInSec = 200_000); e.tick()
        assertEquals("more than a day", e.timeLeft())
        assertEquals("500 MB left · more than a day", e.ongoingNote()!!.body)
    }

    @Test fun failedRenewalWithDataLeftStaysConnected() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); tn.wallet = wallet("54"); tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, bytesLeft = 300_000_000, bytesBought = 660_160_512, payment = T.Payment.FUNDED); e.tick()
        assertEquals(Conn.CONNECTED, e.conn)
        tn.session = tn.session!!.copy(payment = T.Payment.FAILED); e.tick()
        assertEquals("data still flows: not paused", Conn.CONNECTED, e.conn); assertNull(e.pause)
        val m = e.moments().first()
        assertEquals("Payment for more data failed.", m.lead)
        assertEquals("You can keep browsing on the 300 MB left here. Gnosis VPN keeps retrying the payment.", m.text)
        assertEquals("You can keep browsing on the 300 MB left here.", e.simpleLine(m).text)
        assertTrue(e.ongoingNote()!!.title.startsWith("Connected"))
    }

    /** The phone on 2026-11-18: payment failed during the hold, the core retried. Matches gvpndows. */
    @Test fun failedPaymentDuringTheHoldStaysConnectedAndWaiting() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); tn.wallet = wallet("54"); tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, bytesLeft = 0, stalled = true, awaitingPayment = true, payment = T.Payment.FAILED)
        e.tick()
        assertEquals(Conn.CONNECTED, e.conn); assertNull(e.pause)
        val m = e.moments().first()
        assertEquals("Waiting for ${e.ex(e.loc).city} to see your payment.", m.lead)
        assertEquals("The last payment failed; Gnosis VPN retries it automatically. Traffic resumes by itself.", m.text)
        assertNull("balance is fine: no Add funds", m.btns)
        assertTrue(e.ongoingNote()!!.title.startsWith("Connected"))
        // the core reconnects: shown as such, then connected again
        tn.session = T.Session("0xe5cE", 1, reconnecting = true); e.tick(); assertEquals(Conn.RECONNECTING, e.conn)
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, payment = T.Payment.PENDING); e.tick(); assertEquals(Conn.CONNECTED, e.conn)
    }

    @Test fun lowBalanceDuringTheHoldOffersFunds() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); tn.wallet = wallet("5", T.Level.LOW); tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, bytesLeft = 0, stalled = true, awaitingPayment = true, payment = T.Payment.FAILED)
        e.tick()
        assertTrue(e.balanceLow())
        val m = e.moments().first()
        assertTrue(m.text, m.text.endsWith("Your balance is low."))
        assertEquals(listOf("Add funds" to "addData"), m.btns)
    }

    @Test fun refusedConnectSaysWhy() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); e.tick()
        e.act("connect"); tn.state = T.State.CONNECTING; e.tick()
        tn.lastErrorText = "No relay path to the exit right now (0 forward, 2 return)."; tn.state = T.State.ERROR; e.tick()
        val m = e.moments().first()
        assertEquals("Couldn’t connect to ${e.ex(e.loc).city}.", m.lead)
        assertEquals("No relay path to the exit right now (0 forward, 2 return).", m.text)
    }

    @Test fun paymentHoldIsWaitingNotReconnecting() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); tn.wallet = wallet("54"); tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, stalled = true, awaitingPayment = true, payment = T.Payment.PENDING)
        e.tick()
        assertEquals(Conn.CONNECTED, e.conn); assertTrue(e.awaitingPayment)
        val m = e.moments().first()
        assertEquals("Waiting for ${e.ex(e.loc).city} to see your payment.", m.lead)
        assertFalse(m.text.contains("isn’t protected"))
        assertEquals("Waiting for the exit to see your payment; traffic resumes by itself.", e.ongoingNote()!!.body)
        // the hold ends: plain connected
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, bytesLeft = 600_000_000, payment = T.Payment.FUNDED); e.tick()
        assertFalse(e.awaitingPayment)
    }

    @Test fun startupShowsTheCoresIssueInsteadOfTheStep() {
        val (tn, e, _) = setup()
        tn.phase = "warmup"; tn.startup = T.Startup(5, 19, "Connecting to Gnosis Chain"); e.tick()
        assertEquals("Connecting to Gnosis Chain", e.syncStep())
        tn.startup = T.Startup(5, 19, "Connecting to Gnosis Chain", "Can’t reach Gnosis Chain. Check your internet connection; retrying."); e.tick()
        assertEquals("Can’t reach Gnosis Chain. Check your internet connection; retrying.", e.syncStep())
    }

    @Test fun simpleViewKeepsWarningsAndLockdownText() {
        val (_, e, _) = setup()
        assertEquals("", e.simpleLine(Msg(Tone.OK, "check_circle", "Payment received.", "3 GB added.")).text)
        assertEquals("If the VPN drops, traffic goes direct.", e.simpleLine(Msg(Tone.INFO, "info", "Lockdown off.", "If the VPN drops, traffic goes direct.")).text)
        assertEquals("If the VPN drops, nothing gets out.", e.simpleLine(Msg(Tone.OK, "check_circle", "Lockdown on.", "If the VPN drops, nothing gets out.")).text)
        assertEquals("Connect becomes available once it arrives.", e.simpleLine(Msg(Tone.INFO, "hourglass_top", "Waiting for payment…", "Connect becomes available once it arrives.")).text)
    }

    @Test fun pausesFollowTheCore() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger(), uk()); tn.wallet = wallet("0", T.Level.EMPTY)
        tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, bytesLeft = 0, stalled = true, awaitingPayment = true, payment = T.Payment.PENDING)
        e.tick()
        assertEquals("S14", e.screenCode().first)
        // with funds again, a failed payment during the hold is not a pause: connected, waiting (gvpndows)
        tn.wallet = wallet("10"); tn.session = tn.session!!.copy(payment = T.Payment.FAILED); e.tick()
        assertEquals(Conn.CONNECTED, e.conn)
        // the exit went away while reconnecting
        tn.locations = listOf(ger().copy(health = ger().health!!.copy(reachable = false, unrecoverable = true)), uk())
        tn.session = T.Session("0xe5cE", 1, reconnecting = true); e.tick()
        // a reconnect, not "went offline, data lost": the core keeps trying (the phone on 2026-11-18:
        // a Wi-Fi to mobile switch looked exactly like this)
        assertEquals(Conn.RECONNECTING, e.conn); assertNull(e.pause)
        val m = e.moments().first()
        assertEquals("Reconnecting to ${e.ex(e.loc).city}.", m.lead)
        assertFalse(m.text, m.text.contains("lost"))
        assertEquals(listOf("Best available" to "bestAvail"), m.btns)
        assertEquals("Traffic is held until the connection is back.", e.ongoingNote()!!.body)
        e.act("bestAvail")
        assertEquals("0xE7FD", tn.selected.last())
    }

    @Test fun noDemoActions() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); e.tick()
        val before = e.screenCode()
        for (a in listOf("preset", "evOffline", "evPay", "gatePaid", "sess", "bal", "skip", "failShow")) e.act(a, mapOf("v" to "s14"))
        assertEquals(before, e.screenCode())
        assertFalse(e.simulatesSystem)
        assertEquals(0.0, e.balance, 0.0)   // nothing invented before the wallet is known
        assertEquals("—", e.balanceText())
    }

    @Test fun throughputFromCounters() {
        val (tn, e, _) = setup()
        tn.phase = "running"; tn.locations = listOf(ger()); tn.state = T.State.CONNECTED
        tn.session = T.Session("0xe5cE", 1, step = 9, steps = 10, bytesLeft = 600_000_000)
        tn.counters = T.Stats(0, 0, null); e.tick()
        assertNotNull(e.rates())
    }

    @Test fun countryCodesFromPlaces() {
        assertEquals("GB", GnosisEngine.countryCode("Edinburgh, UK"))
        assertEquals("FR", GnosisEngine.countryCode("FR"))
        assertEquals("LT", GnosisEngine.countryCode("Kaunas, LT"))
        assertEquals("", GnosisEngine.countryCode("Bristol"))
    }
}
