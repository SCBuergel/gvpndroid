package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Each visit to Live traffic starts from a fresh Viz, on its own clock from 0 (finding R5-7). */
class R5_7TrafficVisitTest {
    private fun visit(v: Viz, rates: Pair<Double, Double>?, from: Double, to: Double) {
        var t = from; while (t <= to) { v.sample(rates, t); t += 0.05 }
    }

    @Test fun aStaleVizFreezesTheRateWhichIsWhyEachVisitGetsItsOwn() {
        val v = Viz(); visit(v, 8.0 to 1.0, 0.0, 300.0)
        visit(v, 2.0 to 0.5, 0.0, 1.0)   // the next visit's clock restarts at 0
        assertEquals(8.0, v.down, 0.0)
    }

    @Test fun aNewVisitFollowsTheRatesFromItsFirstSecond() {
        visit(Viz(), 8.0 to 1.0, 0.0, 300.0)
        val v = Viz(); visit(v, 2.0 to 0.5, 0.0, 1.0)
        assertEquals(2.0, v.down, 0.0); assertEquals(0.5, v.up, 0.0)
        val off = Viz(); visit(off, null, 0.0, 1.0)
        assertEquals(0.0, off.down, 0.0); assertEquals(0.0, off.up, 0.0)
    }

    @Test fun theTrafficPageRemembersItsOwnViz() {
        val root = File("src/main/java/org/gnosisvpn/stub/ui/Root.kt").readText()
        assertTrue(Regex("""\"traffic\" -> \{ val viz = remember \{ Viz\(\) \}""").containsMatchIn(root))
        assertEquals("one Viz, inside the traffic branch", 1, Regex("""Viz\(\)""").findAll(root).count())
    }
}
