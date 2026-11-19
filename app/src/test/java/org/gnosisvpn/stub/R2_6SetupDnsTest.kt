package org.gnosisvpn.stub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * A setup_tunnel with dns null (the core's config sets none) gets the default server, never a
 * server named "null" (R2-6). setupDns checks isNull, so it reads alike under Android's org.json,
 * whose optString gives "null" there, and the JVM's, whose optString gives "".
 */
class R2_6SetupDnsTest {
    private val samples = JSONObject(File("../rust/gnosis_vpn-android/tests/protocol.json").readText()).getJSONObject("setup_tunnel")

    @Test fun noDnsFromTheCoreUsesTheDefault() {
        val dns = GnosisProtocol.setupDns(samples.getJSONObject("no_dns"))
        assertNull(dns)
        assertEquals(listOf(GnosisProtocol.DEFAULT_DNS), GnosisProtocol.dnsServers(dns))
        assertNull(GnosisProtocol.setupDns(JSONObject("""{"type":"setup_tunnel"}""")))   // absent
    }

    @Test fun theCoresServersAreUsed() {
        val dns = GnosisProtocol.setupDns(samples.getJSONObject("with_dns"))
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), GnosisProtocol.dnsServers(dns))
    }
}
