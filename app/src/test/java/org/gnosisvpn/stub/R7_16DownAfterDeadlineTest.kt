package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * down.sh clears the kept-droplet record once the delete is verified, also when the deadline already
 * fired and its cancel fails; a delete not verified keeps the record (finding R7-16).
 * Fake python3: droplet.py delete exits DEL_EXIT, the deadline's cancel exits CANCEL_EXIT.
 */
class R7_16DownAfterDeadlineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun down(delExit: Int, cancelExit: Int): Triple<Int, File, List<String>> {
        val repo = tmp.newFolder("repo"); val home = tmp.newFolder("home"); val bin = tmp.newFolder("bin")
        val log = File(tmp.root, "calls.log").apply { writeText("") }
        File(repo, "scripts/do-build").mkdirs()
        File("../scripts/do-build/down.sh").copyTo(File(repo, "scripts/do-build/down.sh"))
        val st = File(home, ".local/state/gvpndroid-do").apply { mkdirs() }
        File(st, "current").writeText("id=4242\nname=gvpndroid-build-1\n")
        File(bin, "python3").apply {
            writeText("#!/bin/bash\n[ \"\$1\" = -I ] && shift\n" +
                "case \"\$1\" in */droplet.py) echo \"delete \$3\" >> \"\$LOG\"; exit \$DEL_EXIT ;; *) echo \"\$2 \$3\" >> \"\$LOG\"; exit \$CANCEL_EXIT ;; esac\n")
            setExecutable(true)
        }
        val p = ProcessBuilder("bash", "scripts/do-build/down.sh").directory(repo).redirectErrorStream(true)
        p.environment().apply {
            put("HOME", home.path); put("PATH", "${bin.path}:${get("PATH")}"); put("LOG", log.path); put("DEADLINE", "/fake/deadline")
            put("DEL_EXIT", "$delExit"); put("CANCEL_EXIT", "$cancelExit")
        }
        val proc = p.start(); proc.inputStream.readBytes()
        return Triple(proc.waitFor(), st, log.readLines())
    }

    @Test fun aDeadlineThatAlreadyFiredDoesNotKeepTheRecord() {
        val (exit, st, calls) = down(delExit = 0, cancelExit = 1)
        assertEquals(0, exit)
        assertEquals(listOf("delete 4242", "cancel gvpndroid-build-1"), calls)
        assertFalse(File(st, "current").exists()); assertTrue(File(st, "current.deleted").exists())
    }

    @Test fun theNormalPathCancelsTheDeadline() {
        val (exit, st, calls) = down(delExit = 0, cancelExit = 0)
        assertEquals(0, exit)
        assertEquals(listOf("delete 4242", "cancel gvpndroid-build-1"), calls)
        assertTrue(File(st, "current.deleted").exists())
    }

    @Test fun aDeleteNotVerifiedKeepsTheRecordAndTheDeadline() {
        val (exit, st, calls) = down(delExit = 1, cancelExit = 0)
        assertNotEquals(0, exit)
        assertEquals(listOf("delete 4242"), calls)
        assertTrue(File(st, "current").exists()); assertFalse(File(st, "current.deleted").exists())
    }
}
