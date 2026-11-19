package org.gnosisvpn.stub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** A Connect the core never took (not running, exiting, too slow) is retried, not a refusal (R2-5). */
class R2_5TransientBridgeErrorTest {
    private val errors = JSONObject(File("../rust/gnosis_vpn-android/tests/protocol.json").readText()).getJSONObject("bridge_errors")

    @Test fun transientBridgeErrorsAreNotRefusals() {
        assertNull(GnosisProtocol.connectRefusal(errors.getString("transient")))
        // every message lib.rs marks transient
        for (e in listOf("core is not running", "core stopped", "core dropped the command", "core did not answer within 60 s"))
            assertNull(e, GnosisProtocol.connectRefusal(JSONObject().put("error", e).put("transient", true).toString()))
    }

    @Test fun otherErrorsAndUnreachableExitsStillEndTheAttempt() {
        assertEquals("command is null or not UTF-8", GnosisProtocol.connectRefusal(errors.getString("refused")))
        assertEquals("There is no route to this exit right now.",
            GnosisProtocol.connectRefusal("""{"type":"connect","data":{"type":"unable_to_connect","destination":{},"route_health":{"type":"not_routable"}}}"""))
    }
}
