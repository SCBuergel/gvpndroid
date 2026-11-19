package org.gnosisvpn.stub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * What the UI shows comes from these parsers: exit health, the session with its data chunks, the
 * wallet and start-up steps. Samples follow client 8a0e3be's IPC v3 types
 * (gnosis_vpn-ipc protocol.rs); the running status is a real reply from tests/protocol.json.
 */
class GnosisLiveDataTest {
    private val fixture = JSONObject(File("../rust/gnosis_vpn-android/tests/protocol.json").readText())

    @Test fun newCommandsAreInTheContract() {
        val c = fixture.getJSONObject("commands")
        assertEquals(c.getString("balance"), GnosisProtocol.BALANCE)
        assertEquals(c.getString("nerd_stats"), GnosisProtocol.NERD_STATS)
        assertEquals(c.getString("probing_boost"), GnosisProtocol.PROBING_BOOST)
    }

    @Test fun exitHealthFromARealStatus() {
        val data = GnosisProtocol.statusData(fixture.getJSONObject("status_running").toString())!!
        val d = GnosisProtocol.destinations(data).first { it.name == "GVPN-GER-1" }
        val h = d.health!!
        assertEquals("DE", h.countryCode)
        assertTrue(h.reachable)
        assertEquals(0.7697826838068121, h.pathValue!!, 1e-9)
        assertEquals(3, h.firstRelays)
        assertNull("not checked yet", h.pingMs)
        assertEquals(660160512L, GnosisProtocol.chunkBytes(data))
        assertNull("running: no start-up progress", GnosisProtocol.startup(data))
        assertNull(GnosisProtocol.session(data))
    }

    @Test fun quickCheckGivesLatencyAndLoad() {
        val rh = JSONObject("""{"state":{"type":"routable"},"last_error":null,"walk":null,
            "quick_probe":{"type":"checked","checked_at":1794944505491,"versions":{"api":[]},"api_version":"v1",
              "load":{"slots":{"total":10,"available":4,"connected":6},"load_avg":{"one":1.0,"five":1.0,"fifteen":1.0,"nproc":4}},
              "status_rtt":412}}""")
        val h = GnosisProtocol.health(rh, "GB")
        assertEquals(412, h.pingMs); assertEquals(10, h.slotsTotal); assertEquals(4, h.slotsFree)
        assertEquals(0.25, h.load!!, 1e-9); assertEquals(1794944505491L, h.checkedAtMs)
        val down = GnosisProtocol.health(JSONObject("""{"state":{"type":"unrecoverable","reason":{"type":"not_allowed"}},"last_error":"denied","walk":null,"quick_probe":null}"""), null)
        assertFalse(down.reachable); assertTrue(down.unrecoverable); assertEquals("denied", down.problem)
    }

    private fun status(conn: String) = JSONObject("""{"run_mode":{"type":"running"},"destinations":[],"connection":$conn,"pix_chunk_bytes":660160512}""")

    @Test fun sessionWhileConnecting() {
        val s = GnosisProtocol.session(status("""{"type":"connecting","destination":"gvpn-ger-1-j9os","since":1794944505000,"phase":{"type":"register_wg"},"pix":null}"""))!!
        assertEquals("gvpn-ger-1-j9os", s.exitId)
        assertEquals(4, s.step); assertEquals("Registering with the exit", s.stepName)
        assertFalse(s.reconnecting)
    }

    @Test fun sessionConnectedWithData() {
        val s = GnosisProtocol.session(status("""{"type":"connected","destination":"gvpn-ger-1-j9os","since":1794944505000,"tunnel_ping_rtt":380,"stall":null,
            "pix":{"phase":{"type":"funded"},"chunks_committed":2,"chunks_paid":2,"chunks_failed":0,"chunk_bytes":660160512,
                   "bytes_bought":1320321024,"cost":"11882889216000000000","bytes_spent":700000000,"bytes_left":620321024,"bytes_received":690000000,"empty_in":1800000}}"""))!!
        assertEquals(380, s.pingMs)
        assertEquals(620321024L, s.bytesLeft); assertEquals(1320321024L, s.bytesBought)
        assertEquals(1800L, s.emptyInSec); assertEquals(Tunnel.Payment.FUNDED, s.payment)
        assertEquals(0, BigDecimal("11.882889216").compareTo(s.cost))
        assertEquals(s.steps - 1, s.step)
        assertFalse(s.stalled)
    }

    @Test fun stalledWaitingForPayment() {
        val s = GnosisProtocol.session(status("""{"type":"connected","destination":"x","since":1,"tunnel_ping_rtt":null,
            "stall":{"since":2,"failed_pings":3,"reconnect_at":5,"awaiting_payment_since":3},
            "pix":{"phase":{"type":"payment_failed"},"chunks_committed":1,"chunks_paid":1,"chunks_failed":1,"chunk_bytes":10,"bytes_bought":10,"cost":"0"}}"""))!!
        assertTrue(s.stalled); assertTrue(s.awaitingPayment); assertEquals(Tunnel.Payment.FAILED, s.payment)
        assertNull(s.bytesLeft)
        val r = GnosisProtocol.session(status("""{"type":"reconnecting","destination":"x","since":1,"phase":null}"""))!!
        assertTrue(r.reconnecting); assertNull(r.step)
    }

    @Test fun startupSteps() {
        val w = GnosisProtocol.startup(JSONObject("""{"run_mode":{"type":"warmup","stage":{"type":"connecting_blockchain"},"issue":null}}"""))!!
        assertEquals("Connecting to Gnosis Chain", w.name); assertNull(w.error)
        val stuck = GnosisProtocol.startup(JSONObject("""{"run_mode":{"type":"warmup","stage":{"type":"connecting_blockchain"},
            "issue":{"kind":{"type":"unreachable"},"message":"dns error: failed to lookup address","since":1794944505000}}}"""))!!
        assertEquals("Can’t reach Gnosis Chain. Check your internet connection; retrying.", stuck.error)
        assertEquals("Start-up problem: boom. Retrying.", GnosisProtocol.issue(JSONObject("""{"kind":{"type":"other"},"message":"boom","since":1}""")))
        val later = GnosisProtocol.startup(JSONObject("""{"run_mode":{"type":"warmup","stage":{"type":"announcing_node"},"issue":null}}"""))!!
        assertTrue(later.step > w.step)
        assertNull(GnosisProtocol.startup(JSONObject("""{"run_mode":{"type":"preparing_safe"}}""")))
        assertEquals("Your node stopped", GnosisProtocol.failure(JSONObject("""{"run_mode":{"type":"running","hopr_status":{"type":"failed"}}}""")))
        assertNull(GnosisProtocol.failure(fixture.getJSONObject("status_running").getJSONObject("data")))
    }

    @Test fun walletFromABalanceReply() {
        val reply = """{"type":"balance","data":{"type":"available","node":"4000000000000000","safe":"120500000000000000000","channels_out":[],
            "info":{"node_address":"0xf9f8a1C7aDBb89460F0249A9DC5A9327068EF072","node_peer_id":"12D3","safe_address":"0x01"},
            "capacity_allocations":null,"ideal_balance":null,"pix_pool":null,
            "funding_status":{"traffic":{"type":"low"},"gas":{"type":"good"},"wxhopr_deficit":"20000000000000000000","xdai_deficit":null,"refill_shortfall":null}}}"""
        val w = GnosisProtocol.wallet(reply)!!
        assertEquals(0, BigDecimal("120.5").compareTo(w.traffic)); assertEquals(0, BigDecimal("0.004").compareTo(w.gas))
        assertEquals(Tunnel.Level.LOW, w.trafficLevel); assertEquals(Tunnel.Level.GOOD, w.gasLevel)
        assertEquals(0, BigDecimal(20).compareTo(w.trafficDeficit)); assertNull(w.gasDeficit)
        assertEquals("0xf9f8a1C7aDBb89460F0249A9DC5A9327068EF072", w.address)
        assertNull(GnosisProtocol.wallet("""{"type":"balance","data":{"type":"unavailable","reason":"not running"}}"""))
    }

    @Test fun dataPriceFromTheBundledConfig() {
        val price = GnosisProtocol.pricePerByte(File("src/main/assets/gnosis/config.toml").takeIf { it.exists() }?.readText()
            ?: """price_per_byte = "9 000 000 000 wei wxHOPR"""")!!
        assertEquals(0, BigDecimal("0.000000009").compareTo(price))
        assertNull(GnosisProtocol.pricePerByte("version = 7"))
    }

    @Test fun trafficCounters() {
        val r = GnosisProtocol.rxTx("""{"type":"nerd_stats","data":{"type":"connected","conn":{"wg_stats":{"current":{"at":1,"tx_bytes":500,"rx_bytes":9000,"rtt_ms":null,"estimated_loss":0.0},"history":[]}}}}""")
        assertEquals(9000L to 500L, r)
        assertNull(GnosisProtocol.rxTx("""{"type":"nerd_stats","data":{"type":"no_info"}}"""))
    }

    @Test fun whyAnExitCannotBeReached() {
        assertEquals("No relay path to the exit right now (0 forward, 2 return). Last error: no path found",
            GnosisProtocol.whyNot(JSONObject("""{"state":{"type":"not_routable"},"walk":{"type":"no_path","walked_at":1,"forward_count":0,"return_count":2},"last_error":"no path found","quick_probe":null}""")))
        assertEquals("The exit refused the session: handshake timeout.",
            GnosisProtocol.whyNot(JSONObject("""{"state":{"type":"unrecoverable","reason":{"type":"cannot_open_session","error":"handshake timeout"}},"walk":null,"last_error":null}""")))
        // a connect reply carries only the state
        assertEquals("This exit runs a version this app cannot talk to.",
            GnosisProtocol.whyNot(JSONObject("""{"type":"unrecoverable","reason":{"type":"incompatible_api_version","offered":[]}}""")))
        val h = GnosisProtocol.health(JSONObject("""{"state":{"type":"not_routable"},"walk":{"type":"not_announced","walked_at":1},"last_error":null,"quick_probe":null}"""), null)
        assertEquals("The exit has not announced itself on the network, so there is no way to reach it.", h.why)
        assertNull(GnosisProtocol.health(JSONObject("""{"state":{"type":"routable"},"last_error":null,"walk":null,"quick_probe":null}"""), null).why)
    }

    @Test fun connectRefusals() {
        assertNull(GnosisProtocol.connectRefusal("""{"type":"connect","data":{"type":"connecting","destination":{}}}"""))
        assertNull(GnosisProtocol.connectRefusal("""{"type":"connect","data":{"type":"waiting_to_connect","destination":{},"route_health":{"type":"not_routable"}}}"""))
        assertEquals("There is no route to this exit right now.",
            GnosisProtocol.connectRefusal("""{"type":"connect","data":{"type":"unable_to_connect","destination":{},"route_health":{"type":"not_routable"}}}"""))
        assertEquals("worker offline", GnosisProtocol.connectRefusal("""{"error":"worker offline"}"""))
        // a core that is (re)starting is waited for, not taken as a refusal
        assertNull(GnosisProtocol.connectRefusal("""{"error":"core is not running","transient":true}"""))
        assertEquals("The Gnosis VPN core did not answer.", GnosisProtocol.connectRefusal(null))
    }

    @Test fun verifyPingCountsAsConnected() {
        val s = GnosisProtocol.session(status("""{"type":"connecting","destination":"x","since":1,"phase":{"type":"verify_ping"},"pix":null}"""))!!
        assertEquals(s.steps - 1, s.step)
        val early = GnosisProtocol.session(status("""{"type":"connecting","destination":"x","since":1,"phase":{"type":"establish_wg_tunnel"},"pix":null}"""))!!
        assertTrue(early.step!! < early.steps - 1)
    }

    @Test fun theCoreGaveUpOnlyWithoutATarget() {
        // the core dropped the attempt: say why
        assertTrue(GnosisProtocol.coreGaveUp(true, false, "connecting", "disconnected", null))
        assertTrue(GnosisProtocol.coreGaveUp(true, false, "reconnecting", "disconnected", ""))
        // switching exits, probing or waiting for a route: "disconnected" but the core keeps a target
        assertFalse(GnosisProtocol.coreGaveUp(true, false, "connecting", "disconnected", "gvpn-fra-1-xp4e"))
        // a Connect is being sent, the user disconnected, or it was never an attempt
        assertFalse(GnosisProtocol.coreGaveUp(true, true, "connecting", "disconnected", null))
        assertFalse(GnosisProtocol.coreGaveUp(false, false, "connecting", "disconnected", null))
        assertFalse(GnosisProtocol.coreGaveUp(true, false, "disconnected", "disconnected", null))
        assertFalse(GnosisProtocol.coreGaveUp(true, false, "connected", "disconnected", null))
    }
}
