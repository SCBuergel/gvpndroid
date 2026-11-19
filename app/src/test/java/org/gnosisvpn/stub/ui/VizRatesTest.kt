package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Live traffic shows only measured rates, never invented ones (finding R5-1). */
class VizRatesTest {
    @Test
    fun noRatesReadZeroAndLeaveThePeaks() {
        val v = Viz()
        for (i in 1..50) v.sample(null, i * 0.1)   // off, connecting or paused
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), listOf(v.down, v.up, v.dpk, v.upk))
        v.sample(0.0 to 0.0, 6.0)                   // on, before the first sample
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), listOf(v.down, v.up, v.dpk, v.upk))
    }

    @Test
    fun peaksFollowRealRatesOnly() {
        val v = Viz()
        v.sample(3.0 to 1.0, 1.0)
        v.sample(2.0 to 0.5, 1.5)
        assertEquals(listOf(2.0, 0.5, 3.0, 1.0), listOf(v.down, v.up, v.dpk, v.upk))
        v.sample(null, 2.0)
        assertEquals(listOf(0.0, 0.0, 3.0, 1.0), listOf(v.down, v.up, v.dpk, v.upk))
    }
}
