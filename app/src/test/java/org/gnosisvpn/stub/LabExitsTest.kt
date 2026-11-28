package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The developer screen's lab-exit toggle: pinned destinations only, removable, nothing else touched. */
class LabExitsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val base = "version = 7\ngeoip_db = \"/g\"\n\n[pix_strategy]\nprice_per_byte = \"1 wei wxHOPR\"\n"

    private val sample = listOf(LabExits.Exit("lab", "0xa532c96b39275210a3756c93f84338bd4661a55d", "Lab exit Frankfurt", "vpn-lab"))
    private val oldBlock = "\n# --- lab exits (developer screen toggle) ---\n[destinations.lab]\naddress = \"0xa532c96b39275210a3756c93f84338bd4661a55d\"\n" +
        "path = { hops = 1 }\nmeta = { name = \"Lab exit Frankfurt\", location = \"vpn-lab\", sidecar = \"on\" }\n\n# --- end lab exits ---\n"

    @Test fun onAddsEveryLabExitAsAPlainDestination() {
        val on = LabExits.apply(base, true, sample)
        assertTrue(on.startsWith(base.trimEnd('\n')))
        sample.forEach {
            assertTrue(on.contains("[destinations.${it.key}]\naddress = \"${it.address}\"\npath = { hops = 1 }\n"))
            assertTrue(on.contains("name = \"${it.name}\""))
        }
        // "completely normal fashion": no relay allowlist, channel strategy or label comes with it
        assertFalse(on.contains("channel_allowlist")); assertFalse(on.contains("[strategy")); assertFalse(on.contains("sidecar"))
    }

    @Test fun offRestoresTheConfigAndOnIsIdempotent() {
        val on = LabExits.apply(base, true, sample)
        assertEquals(on, LabExits.apply(on, true, sample))
        assertEquals(base.trimEnd('\n'), LabExits.apply(on, false, sample).trimEnd('\n'))
        assertEquals(base, LabExits.apply(base, false, sample))
    }

    @Test fun anOlderLabBlockIsReplacedNotDuplicated() {
        val now = LabExits.apply(base + oldBlock, true, sample)
        assertEquals(1, Regex("""\[destinations\.lab]""").findAll(now).count())
        assertFalse("the side-car label of 0.0.5 is gone", now.contains("sidecar"))
    }

    /** No lab exits exist now: an install that had them on gets the block removed at the next core start. */
    @Test fun withoutLabExitsTheToggleIsOffAndAnOldBlockIsDropped() {
        assertTrue(LabExits.EXITS.isEmpty())
        assertEquals(base.trimEnd('\n'), LabExits.apply(base + oldBlock, true).trimEnd('\n'))
        val nb = tmp.newFolder()
        File(nb, "gnosis.toml").writeText(base + oldBlock)
        File(nb, "lab-exits-on").writeText("")
        assertFalse(LabExits.isOn(nb))
        assertNull(LabExits.set(nb, true) { null })
        assertFalse(LabExits.isOn(nb)); assertFalse(File(nb, "gnosis.toml").readText().contains("destinations"))
    }

    @Test fun setWritesOnlyAValidConfig() {
        val nb = tmp.newFolder()
        assertNotNull(LabExits.set(nb, true) { null })           // no gnosis.toml yet
        File(nb, "gnosis.toml").writeText(base)
        assertNotNull(LabExits.set(nb, true) { "bad" })          // rejected: nothing changes
        assertEquals(base, File(nb, "gnosis.toml").readText())
    }
}
