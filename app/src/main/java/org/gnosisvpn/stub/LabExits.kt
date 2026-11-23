package org.gnosisvpn.stub

import java.io.File

/**
 * Developer screen toggle: the vpn-lab's exits, which the network does not announce, pinned into
 * gnosis.toml as extra [destinations] next to the discovered ones. Nothing else changes: no relay
 * allowlist, the 1-hop path the lab's Linux clients use, so the core treats them like any other exit,
 * except that it pays them through the relayer side-car (label sidecar = "on"). The block is marked so it
 * can be removed again; the flag survives app updates (GnosisSetup.enable re-applies it).
 */
object LabExits {
    data class Exit(val key: String, val address: String, val name: String, val location: String)

    val EXITS = listOf(
        // Temporary DigitalOcean machines: Frankfurt is deleted 2026-11-23 22:30 UTC, New York 2026-11-24 22:35 UTC.
        Exit("lab", "0xa532c96b39275210a3756c93f84338bd4661a55d", "Lab exit Frankfurt", "vpn-lab"),
        Exit("lab2", "0xe18960da6c134aa4efd1de90547bcd6d032557b6", "Lab exit New York", "vpn-lab2"),
    )

    private const val BEGIN = "# --- lab exits (developer screen toggle) ---"
    private const val END = "# --- end lab exits ---"
    private const val FLAG = "lab-exits-on"

    fun isOn(noBackup: File) = File(noBackup, FLAG).exists()

    /** [config] without any lab block, plus a fresh one when [on]. */
    fun apply(config: String, on: Boolean): String {
        val start = config.indexOf(BEGIN)
        val base = if (start < 0) config else {
            val stop = config.indexOf(END, start).let { if (it < 0) config.length else it + END.length }
            config.substring(0, start) + config.substring(stop).trimStart('\n')
        }
        if (!on) return base
        return buildString {
            append(base.trimEnd('\n')).append("\n\n").append(BEGIN).append('\n')
            EXITS.forEach {
                append("[destinations.${it.key}]\n")
                append("address = \"${it.address}\"\n")
                append("path = { hops = 1 }\n")
                // sidecar = "on": both lab exits allow the Curvy relayer as a session target (rust/patches: android_sidecar)
                append("meta = { name = \"${it.name}\", location = \"${it.location}\", sidecar = \"on\" }\n\n")
            }
            append(END).append('\n')
        }
    }

    /**
     * Turns the lab exits on or off in noBackup/gnosis.toml. [check] validates the new config
     * (GnosisNative.checkConfig: null when fine); on a rejection nothing is written. Returns an
     * error, or null when done. The core reads the file when it (re)starts.
     */
    fun set(noBackup: File, on: Boolean, check: (String) -> String?): String? {
        val toml = File(noBackup, "gnosis.toml")
        if (!toml.isFile) return "no Gnosis config yet"
        val next = apply(toml.readText(), on)
        check(next)?.let { return "config rejected: $it" }
        toml.writeText(next)
        val flag = File(noBackup, FLAG)
        if (on) flag.writeText("") else flag.delete()
        return null
    }
}
