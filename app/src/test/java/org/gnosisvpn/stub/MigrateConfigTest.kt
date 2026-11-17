package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MigrateConfigTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun movesOldConfig() {
        val old = tmp.newFile("old.conf").apply { writeText("A") }
        val new = File(tmp.newFolder("nobackup"), "tunnel.conf")
        migrateConfig(old, new)
        assertFalse(old.exists())
        assertEquals("A", new.readText())
    }

    @Test
    fun keepsNewConfigAndDropsOld() {
        val old = tmp.newFile("old.conf").apply { writeText("A") }
        val new = tmp.newFile("tunnel.conf").apply { writeText("B") }
        migrateConfig(old, new)
        assertFalse(old.exists())
        assertEquals("B", new.readText())
    }

    @Test
    fun keepsOldConfigWhenMoveFails() {
        val old = tmp.newFile("old.conf").apply { writeText("A") }
        // The parent is a regular file, so both the move and the copy fail.
        val new = File(tmp.newFile("not-a-dir"), "tunnel.conf")
        migrateConfig(old, new)
        assertTrue(old.exists())
        assertEquals("A", old.readText())
    }
}
