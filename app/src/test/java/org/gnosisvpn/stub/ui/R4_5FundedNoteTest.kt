package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.gnosisvpn.stub.Tunnel as T

/** "Payment received / Your node is funded" only when the core moves past the gate, once per gate (finding R4-5). */
class R4_5FundedNoteTest {
    private val tn = FakeTunnel()
    private val e = GnosisEngine({ tn }).also { it.hooks = object : EngineHooks {}; it.start() }
    private fun tick() { e.t += 1; e.step(1.0) }
    private fun funded() = e.notes.count { it.code == "N8" }
    private fun gate() { tn.phase = "preparing_safe"; tn.fundingRequest = T.FundingRequest("0xnode", "1 wxHOPR"); tick() }

    @Test fun aNormalFundingIsAnnouncedOnce() {
        gate(); assertTrue(e.gate)
        tn.fundingRequest = null; tn.phase = "warmup"; tick()       // the same core moves on
        tn.phase = "running"; tick(); tick()
        assertFalse(e.gate)
        assertEquals(1, funded())
    }

    @Test fun aVanishedStatusIsNoPayment() {
        gate()
        tn.fundingRequest = null; tn.phase = null; tick()          // shutdown: Try again, or a backend switch
        assertFalse(e.gate)
        tn.phase = "warmup"; tick()                                // the restarted core starts up
        assertEquals(0, funded())
    }

    @Test fun theGateReopensAndTheRealFundingIsStillAnnounced() {
        gate()
        tn.fundingRequest = null; tn.phase = null; tick()
        gate(); assertTrue(e.gate)                                 // the new core still waits for funds
        tn.fundingRequest = null; tn.phase = "running"; tick(); tick()
        assertEquals(1, funded())
        gate(); tn.fundingRequest = null; tn.phase = "running"; tick()   // a later episode: announced again
        assertEquals(2, funded())
    }

    @Test fun fundedWhileTheCoreRestartedIsAnnounced() {
        gate()
        tn.fundingRequest = null; tn.phase = null; tick()
        tn.phase = "running"; tick()                               // the new core finds the funds
        assertEquals(1, funded())
    }
}
