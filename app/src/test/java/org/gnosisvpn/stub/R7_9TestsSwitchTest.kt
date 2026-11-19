package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** TESTS reaches build-remote.sh from rebuild.sh, and only 0 or 1 is accepted (R7-9). */
class R7_9TestsSwitchTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Runs scripts/do-build/[script] with fake ssh/scp; returns its exit code, output and the remote commands. */
    private fun run(script: String, tests: String?): Triple<Int, String, String> {
        val root = tmp.newFolder("repo")
        File(root, "scripts/do-build").mkdirs()
        for (f in listOf(script, "lib.sh")) File("../scripts/do-build/$f").copyTo(File(root, "scripts/do-build/$f"))
        val home = tmp.newFolder("home")
        File(home, ".local/state/gvpndroid-do").apply { mkdirs() }.resolve("current")
            .writeText("ip=192.0.2.1\nknown_hosts=/dev/null\n")
        val log = File(tmp.root, "ssh.log")
        val bin = tmp.newFolder("bin")
        File(bin, "ssh").writeText("#!/bin/bash\nprintf '%s\\n' \"\${@: -1}\" >> \"\$SSH_LOG\"\n")
        File(bin, "scp").writeText("#!/bin/bash\nd=\"\${@: -1}\"\ncase \"\$d\" in root@*) ;; *) echo new > \"\$d\" ;; esac\n")
        bin.listFiles()!!.forEach { it.setExecutable(true) }
        val p = ProcessBuilder("bash", "scripts/do-build/$script").directory(root).redirectErrorStream(true)
        p.environment().apply {
            put("HOME", home.path); put("PATH", "${bin.path}:${get("PATH")}"); put("SSH_LOG", log.path); put("ABIS", "x86_64")
            if (tests != null) put("TESTS", tests) else remove("TESTS")
        }
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        return Triple(proc.waitFor(), out, if (log.exists()) log.readText() else "")
    }

    @Test fun rebuildForwardsTestsAndRunsThemByDefault() {
        val (exit, _, remote) = run("rebuild.sh", null)
        assertEquals(0, exit)
        assertTrue(remote, remote.contains("TESTS=1 bash /root/upload/build-remote.sh"))
        tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
        val (exit0, _, remote0) = run("rebuild.sh", "0")
        assertEquals(0, exit0)
        assertTrue(remote0, remote0.contains("TESTS=0 bash /root/upload/build-remote.sh"))
    }

    @Test fun anythingButZeroOrOneIsRefusedBeforeReachingTheDroplet() {
        for (script in listOf("rebuild.sh", "run.sh")) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val (exit, out, remote) = run(script, "1; touch /tmp/x")
            assertEquals(out, 2, exit)
            assertTrue(out, out.contains("TESTS must be 0 or 1"))
            assertEquals("", remote)
        }
        assertFalse(File(tmp.root, "home/.local/state/gvpndroid-do/id_ed25519").exists())   // run.sh stopped first
    }
}
