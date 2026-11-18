package org.gnosisvpn.stub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The app's side of the contract in rust/gnosis_vpn-android/tests/protocol.json; the crate's
 * tests check the same file against the real client types.
 */
class GnosisProtocolTest {
    private val fixture = JSONObject(File("../rust/gnosis_vpn-android/tests/protocol.json").readText())

    @Test
    fun commandsMatchTheContract() {
        val commands = fixture.getJSONObject("commands")
        assertEquals(commands.getString("status"), GnosisProtocol.STATUS)
        assertEquals(commands.getString("disconnect"), GnosisProtocol.DISCONNECT)
        assertEquals(JSONObject(commands.getString("connect")).toMap(), JSONObject(GnosisProtocol.connect("gvpn-ger-1-j9os")).toMap())
    }

    @Test
    fun locationsFromARealStatus() {
        val data = GnosisProtocol.statusData(fixture.getJSONObject("status_running").toString())!!
        val expected = fixture.getJSONObject("expected").getJSONArray("locations").let { arr ->
            (0 until arr.length()).map { arr.getJSONArray(it).let { e -> Tunnel.Location(e.getString(0), e.getString(1), e.getString(2)) } }
        }
        assertEquals(expected, GnosisProtocol.destinations(data).map { it.toLocation().copy(health = null) })
        assertEquals("running", GnosisProtocol.runMode(data))
        assertNull(GnosisProtocol.fundingRequest(data))
    }

    @Test
    fun fundingRequestWhileWaitingForFunds() {
        val data = GnosisProtocol.statusData(fixture.getJSONObject("status_preparing_safe").toString())!!
        val request = GnosisProtocol.fundingRequest(data)!!
        assertEquals("0xf9f8a1C7aDBb89460F0249A9DC5A9327068EF072", request.address)
        assertEquals(fixture.getJSONObject("expected").getString("funding_need"), request.need)
    }

    @Test
    fun errorRepliesAreNotStatus() {
        assertNull(GnosisProtocol.statusData("""{"error":"core is not running"}"""))
    }

    @Test
    fun tokensFromWei() {
        assertEquals("0.005", GnosisProtocol.tokens("5000000000000000"))
        assertEquals("225.35", GnosisProtocol.tokens("225350000000000000000"))
        assertEquals("?", GnosisProtocol.tokens(null))
    }
}
