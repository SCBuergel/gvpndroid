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

    @Test fun onAddsEveryLabExitAsAPlainDestination() {
        val on = LabExits.apply(base, true)
        assertTrue(on.startsWith(base.trimEnd('\n')))
        LabExits.EXITS.forEach {
            assertTrue(on.contains("[destinations.${it.key}]\naddress = \"${it.address}\"\npath = { hops = 1 }\n"))
            assertTrue(on.contains("name = \"${it.name}\""))
            assertTrue("lab exits ask for the side-car", on.contains("sidecar = \"on\""))
        }
        // "completely normal fashion": no relay allowlist or channel strategy comes with it
        assertFalse(on.contains("channel_allowlist")); assertFalse(on.contains("[strategy"))
    }

    @Test fun offRestoresTheConfigAndOnIsIdempotent() {
        val on = LabExits.apply(base, true)
        assertEquals(on, LabExits.apply(on, true))
        assertEquals(base.trimEnd('\n'), LabExits.apply(on, false).trimEnd('\n'))
        assertEquals(base, LabExits.apply(base, false))
    }

    @Test fun anOlderLabBlockIsReplacedNotDuplicated() {
        val old = base + "\n# --- lab exits (developer screen toggle) ---\n[destinations.lab]\naddress = \"0xa532c96b39275210a3756c93f84338bd4661a55d\"\n# --- end lab exits ---\n"
        val now = LabExits.apply(old, true)
        assertEquals(1, Regex("""\[destinations\.lab]""").findAll(now).count())
        assertTrue(now.contains("sidecar = \"on\""))
    }

    @Test fun setWritesOnlyAValidConfigAndKeepsTheFlag() {
        val nb = tmp.newFolder()
        assertNotNull(LabExits.set(nb, true) { null })           // no gnosis.toml yet
        File(nb, "gnosis.toml").writeText(base)
        assertNotNull(LabExits.set(nb, true) { "bad" })          // rejected: nothing changes
        assertEquals(base, File(nb, "gnosis.toml").readText()); assertFalse(LabExits.isOn(nb))
        assertNull(LabExits.set(nb, true) { null })
        assertTrue(LabExits.isOn(nb)); assertTrue(File(nb, "gnosis.toml").readText().contains("[destinations.lab2]"))
        assertNull(LabExits.set(nb, false) { null })
        assertFalse(LabExits.isOn(nb)); assertFalse(File(nb, "gnosis.toml").readText().contains("destinations"))
    }
}
