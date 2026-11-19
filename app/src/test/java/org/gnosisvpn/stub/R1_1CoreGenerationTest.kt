package org.gnosisvpn.stub

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One HOPR node per identity: an ended core is torn down, and its late events steer no later core. */
class R1_1CoreGenerationTest {
    @Test
    fun eventsCountOnlyForTheirOwnCore() {
        val event = JSONObject("""{"type":"setup_tunnel","generation":3,"request_id":0}""")
        assertTrue(GnosisProtocol.ofCore(event, 3))
        assertFalse(GnosisProtocol.ofCore(event, 4))
        assertFalse(GnosisProtocol.ofCore(JSONObject("""{"type":"setup_tunnel","request_id":0}"""), 4))
    }

    /** The patch makes every return of Core::start shut the node down (in-process, no exit does). */
    @Test
    fun patchTearsDownTheCoreOnEveryReturn() {
        val patch = File("../rust/patches/0001-android-target.patch").readText()
        val hunk = patch.substringAfter("+++ b/gnosis_vpn-lib/src/core/mod.rs", "")
            .substringBefore("diff --git")
        assertTrue("Core::start teardown hunk missing", hunk.contains("_ = probing_tick.tick() => self.sync_network_probing(),"))
        assertTrue(hunk.contains("+        if !matches!(self.phase, Phase::ShuttingDown) {\n+            self.shut_down().await;"))
    }
}
