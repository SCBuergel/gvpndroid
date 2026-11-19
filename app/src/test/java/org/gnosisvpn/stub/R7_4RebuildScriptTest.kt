package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** scripts/do-build/rebuild.sh with fake ssh/scp: a failed remote build must stop before any copy. */
class R7_4RebuildScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(sshOutput: String, sshExit: Int): Pair<Int, File> {
        val root = tmp.newFolder("repo")
        File(root, "scripts/do-build").mkdirs()
        File("../scripts/do-build/rebuild.sh").copyTo(File(root, "scripts/do-build/rebuild.sh"))
        val home = tmp.newFolder("home")
        File(home, ".local/state/gvpndroid-do").apply { mkdirs() }.resolve("current")
            .writeText("ip=192.0.2.1\nknown_hosts=/dev/null\n")
        val bin = tmp.newFolder("bin")
        File(bin, "ssh").writeText("#!/bin/bash\nprintf '%s\\n' \"\$FAKE_OUT\"\nexit \$FAKE_EXIT\n")
        // Only the download into jniLibs has a local destination; uploads go to root@...
        File(bin, "scp").writeText("#!/bin/bash\nd=\"\${@: -1}\"\ncase \"\$d\" in root@*) ;; *) echo new > \"\$d\" ;; esac\n")
        bin.listFiles()!!.forEach { it.setExecutable(true) }
        val p = ProcessBuilder("bash", "scripts/do-build/rebuild.sh").directory(root).redirectErrorStream(true)
        p.environment().apply {
            put("HOME", home.path); put("PATH", "${bin.path}:${get("PATH")}")
            put("FAKE_OUT", sshOutput); put("FAKE_EXIT", sshExit.toString()); put("ABIS", "x86_64")
        }
        val proc = p.start()
        proc.inputStream.readBytes()
        return proc.waitFor() to File(root, "app/src/main/jniLibs/x86_64/libgnosisvpn.so")
    }

    @Test
    fun failedBuildExitsNonzeroAndCopiesNothing() {
        val (exit, lib) = run("STEP test FAILED", 1)
        assertNotEquals(0, exit)
        assertFalse(lib.exists())
    }

    @Test
    fun buildWithNoMatchingOutputSucceedsAndCopies() {
        val (exit, lib) = run("Compiling something", 0)
        assertEquals(0, exit)
        assertEquals("new\n", lib.readText())
    }
}
