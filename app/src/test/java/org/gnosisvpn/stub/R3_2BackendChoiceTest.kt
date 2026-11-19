package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** A WireGuard choice survives a new process; backends swap only while the tunnel is down (R3-2). */
class R3_2BackendChoiceTest {
    @Test
    fun wireGuardChoiceIsRecordedAndCleared() {
        val nb = Files.createTempDirectory("nb").toFile()
        try {
            assertFalse(GnosisSetup.choseWireGuard(nb))        // fresh install: auto setup may run
            GnosisSetup.chooseWireGuard(nb, true)
            assertTrue(GnosisSetup.choseWireGuard(nb))         // the next process keeps WireGuard
            GnosisSetup.chooseWireGuard(nb, false)
            assertFalse(GnosisSetup.choseWireGuard(nb))
        } finally { nb.deleteRecursively() }
    }

    @Test
    fun swapOnlyWhileTheTunnelIsDown() {
        val swappable = Tunnel.State.entries.filter { App.canSwapBackend(it) }
        assertEquals(listOf(Tunnel.State.DISCONNECTED, Tunnel.State.ERROR), swappable)
    }
}
