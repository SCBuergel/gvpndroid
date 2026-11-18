package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine against the prototype (~/mobile-UI src/shared/engine.js): its scenarios reach the
 * screens the prototype's deep links show, and the rules (renewals, notifications, the pay flow)
 * behave the same. Timers (Handler) are inert in unit tests, so time is advanced with step().
 */
class EngineTest {
    private fun engine() = Engine().also { it.reset() }

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
        val e = Engine(); e.start()
        assertEquals("I-1", e.screenCode().first)
        e.act("gateDirect")
        assertEquals("I-1d", e.screenCode().first)
        e.act("back"); assertEquals("I-1w", e.screenCode().first)
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

class GnosisEngineTest {
    @Test fun countryCodesFromPlaces() {
        assertEquals("GB", GnosisEngine.countryCode("Edinburgh, UK"))
        assertEquals("FR", GnosisEngine.countryCode("FR"))
        assertEquals("LT", GnosisEngine.countryCode("Kaunas, LT"))
        assertEquals("", GnosisEngine.countryCode("Bristol"))
    }
}
