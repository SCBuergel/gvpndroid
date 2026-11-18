package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Test

/** LogBuffer.since: what the debug log relay reads every 10 s. */
class LogBufferTest {
    @Test fun sinceReturnsNewLinesAndCountsWhatTheRingDropped() {
        val start = LogBuffer.count()
        LogBuffer.logCore("first"); LogBuffer.logCore("second")
        val (lines, next, lost) = LogBuffer.since(start)
        assertEquals(listOf("core first", "core second"), lines.map { it.substringAfter("  ") })
        assertEquals(start + 2, next); assertEquals(0L, lost)
        assertEquals(Triple(emptyList<String>(), next, 0L), LogBuffer.since(next))
        // more than the ring holds (2000 lines): the oldest wanted ones are reported as lost
        repeat(2500) { LogBuffer.logCore("x$it") }
        val (kept, after, gone) = LogBuffer.since(next)
        assertEquals(2000, kept.size); assertEquals(next + 2500, after); assertEquals(500L, gone)
        assertEquals("core x500", kept.first().substringAfter("  "))
    }
}
