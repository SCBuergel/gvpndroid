package org.gnosisvpn.stub

import org.json.JSONObject

/**
 * The JSON contract with the Rust core (rust/gnosis_vpn-android): commands are the client's
 * `WorkerCommand` (serde tag "type", snake_case), replies its `Response`. Pure functions, so the
 * unit tests can check them against rust/gnosis_vpn-android/tests/protocol.json, which the Rust
 * tests check against the real client types.
 */
object GnosisProtocol {
    const val STATUS = """{"type":"status"}"""
    const val DISCONNECT = """{"type":"disconnect"}"""

    fun connect(destinationId: String): String = JSONObject().put("type", "connect").put("destination", destinationId).toString()

    /** Connect reply types meaning "accepted, the tunnel will follow". */
    val CONNECT_ACCEPTED = setOf("connecting", "already_connected", "waiting_to_connect")

    /** An exit as the core reports it; [id] is the core's per-run destination id. */
    data class Destination(val id: String, val address: String, val name: String, val place: String) {
        /** For the UI: keyed by the exit address, which stays the same across app starts. */
        fun toLocation() = Tunnel.Location(address, name, place)
    }

    /** The `data` object of a status reply, or null if [reply] is an error. */
    fun statusData(reply: String): JSONObject? = runCatching { JSONObject(reply).getJSONObject("data") }.getOrNull()

    fun runMode(data: JSONObject): String? = data.optJSONObject("run_mode")?.optString("type")

    /** Exits from a status `data` object, sorted by name. */
    fun destinations(data: JSONObject): List<Destination> {
        val arr = data.optJSONArray("destinations") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i).let { it.optJSONObject("destination") ?: it }
            val meta = d.optJSONObject("meta")
            val other = meta?.optJSONObject("other")
            val geo = d.optJSONObject("geo")
            val place = listOfNotNull(
                other?.optString("location")?.takeIf { it.isNotEmpty() },
                other?.optString("flag")?.takeIf { it.isNotEmpty() } ?: geo?.optString("country")?.takeIf { it.isNotEmpty() },
            ).joinToString(", ")
            val name = meta?.optString("name")?.takeIf { it.isNotEmpty() } ?: d.optString("id")
            Destination(d.optString("id"), d.optString("address"), name, place)
        }.sortedBy { it.name }
    }

    /** What a node in run mode preparing_safe waits for, or null in any other run mode. */
    fun fundingRequest(data: JSONObject): Tunnel.FundingRequest? {
        val mode = data.optJSONObject("run_mode") ?: return null
        if (mode.optString("type") != "preparing_safe") return null
        val rec = mode.optJSONObject("balance_recommendation")
        val need = "${tokens(rec?.optString("wxhopr"))} wxHOPR + ${tokens(rec?.optString("xdai"))} xDAI"
        return Tunnel.FundingRequest(mode.optString("node_address"), need)
    }

    /** Wei (18 decimals) as a plain decimal string. */
    fun tokens(wei: String?): String =
        wei?.toBigDecimalOrNull()?.movePointLeft(18)?.stripTrailingZeros()?.toPlainString() ?: "?"
}
