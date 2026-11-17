package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLevelTest {
    @Test
    fun cyclesThroughAllLevels() {
        var level = LogLevel.NORMAL
        val seen = mutableListOf<LogLevel>()
        repeat(LogLevel.entries.size) { seen += level; level = level.next() }
        assertEquals(LogLevel.entries.toList(), seen)
        assertEquals(LogLevel.NORMAL, level)
    }

    @Test
    fun everyLevelMutesPathSelectionChatter() {
        // Path-selection errors flood the log by the million if not muted.
        // They are logged at ERROR, so a "warn" base level alone does not mute them.
        LogLevel.entries.forEach { assertTrue(it.name, it.coreFilter.split(",").contains("hopr_transport::path=off")) }
    }
}
