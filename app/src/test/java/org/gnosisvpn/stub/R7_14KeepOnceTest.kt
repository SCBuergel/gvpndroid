package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * KEEP=1 run.sh never loses track of a kept droplet: only one is recorded in $ST/current (R7-14).
 * Fakes stand in for ssh, scp, ssh-keygen, droplet.py and the deadline: nothing leaves the machine.
 */
class R7_14KeepOnceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val state get() = File(tmp.root, "home/.local/state/gvpndroid-do")
    private val calls get() = File(tmp.root, "droplet.log").let { if (it.exists()) it.readText() else "" }

    /** KEEP=1 run.sh; [keptMeanwhile]: another run records its droplet while this one builds. */
    private fun run(keptMeanwhile: Boolean = false): Pair<Int, String> {
        val root = tmp.newFolder("repo")
        File(root, "scripts/do-build").mkdirs()
        for (f in listOf("run.sh", "lib.sh")) File("../scripts/do-build/$f").copyTo(File(root, "scripts/do-build/$f"))
        File(root, "scripts/do-build/droplet.py").writeText("""
import os, sys
open(os.environ["DROPLET_LOG"], "a").write(" ".join(sys.argv[1:2]) + "\n")
if sys.argv[1] == "create":
    if os.environ.get("KEPT_MEANWHILE"): open(os.environ["HOME"] + "/.local/state/gvpndroid-do/current", "w").write("id=999\n")
    print("123", flush=True); print("192.0.2.1 0.1")
""")
        val deadline = File(tmp.root, "deadline.py").apply { writeText("") }
        val bin = tmp.newFolder("bin")
        File(bin, "ssh").writeText("#!/bin/bash\nc=\"\${@: -1}\"\ncase \"\$c\" in *setup-remote.sh*) echo SETUP-DONE ;; *'then echo 1; else echo 0'*) echo 1 ;; esac\n")
        File(bin, "ssh-keygen").writeText("#!/bin/bash\nf=\"\${@: -1}\"; : > \"\$f\"; : > \"\$f.pub\"\n")
        File(bin, "scp").writeText("#!/bin/bash\nd=\"\${@: -1}\"\ncase \"\$d\" in root@*) ;; *) echo new > \"\$d\" ;; esac\n")
        bin.listFiles()!!.forEach { it.setExecutable(true) }
        val p = ProcessBuilder("bash", "scripts/do-build/run.sh").directory(root).redirectErrorStream(true)
        p.environment().apply {
            put("HOME", File(tmp.root, "home").path); put("PATH", "${bin.path}:${get("PATH")}"); put("ABIS", "x86_64")
            put("DEADLINE", deadline.path); put("DROPLET_LOG", File(tmp.root, "droplet.log").path); put("KEEP", "1")
            remove("PROFILE"); remove("TESTS"); if (keptMeanwhile) put("KEPT_MEANWHILE", "1")
        }
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        return proc.waitFor() to out
    }

    @Test fun aKeptDropletIsRecorded() {
        val (exit, out) = run()
        assertEquals(out, 0, exit)
        assertTrue(File(state, "current").readText().startsWith("id=123\n"))
    }

    @Test fun aSecondKeepIsRefusedBeforeAnyDropletIsCreated() {
        state.mkdirs(); File(state, "current").writeText("id=777\n")
        val (exit, out) = run()
        assertNotEquals(0, exit)
        assertTrue(out, out.contains("down.sh"))
        assertEquals("", calls)
        assertEquals("id=777\n", File(state, "current").readText())
    }

    @Test fun aDropletKeptMeanwhileIsNotOverwrittenAndThisOneIsDeleted() {
        val (exit, out) = run(keptMeanwhile = true)
        assertEquals(out, 0, exit)
        assertEquals("id=999\n", File(state, "current").readText())
        assertEquals(listOf("create", "delete"), calls.trim().lines())
    }
}
