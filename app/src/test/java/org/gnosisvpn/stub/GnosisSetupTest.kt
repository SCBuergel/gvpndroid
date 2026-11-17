package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnosisSetupTest {
    private val template = "version = 7\n\n[pix_strategy]\nprice_per_byte = \"9 wxHOPR\"\n"

    @Test
    fun pathsComeBeforeTheFirstTableAndVersionIsNotDuplicated() {
        val config = GnosisSetup.assembleConfig(template, "/d/geoip.mmdb", "/d/curvy-zk")
        val lines = config.lines()
        assertEquals("version = 7", lines[0])
        assertEquals(1, lines.count { it.startsWith("version") })
        val firstTable = lines.indexOfFirst { it.startsWith("[") }
        assertTrue(lines.indexOf("geoip_db = \"/d/geoip.mmdb\"") in 1 until firstTable)
        assertTrue(lines.indexOf("curvy_zk_dir = \"/d/curvy-zk\"") in 1 until firstTable)
        assertTrue(config.contains("price_per_byte = \"9 wxHOPR\""))
    }
}
