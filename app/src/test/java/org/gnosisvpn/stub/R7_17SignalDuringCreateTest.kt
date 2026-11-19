package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A Ctrl-C or TERM to run.sh's or app.sh's process group while droplet.py create runs still deletes
 * the droplet (R7-17). A fake droplet.py logs its calls; nothing leaves the machine.
 */
class R7_17SignalDuringCreateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val log get() = File(tmp.root, "droplet.log").let { if (it.exists()) it.readText() else "" }

    /** Starts [script] in its own process group, sends [signal] to the group once create started; exit code. */
    private fun interrupt(script: String, signal: String, failDelete: Boolean = false): Pair<Int, String> {
        val root = tmp.newFolder("repo")
        File(root, "scripts/do-build").mkdirs()
        for (f in listOf(script, "lib.sh")) File("../scripts/do-build/$f").copyTo(File(root, "scripts/do-build/$f"))
        // create: logs, takes 2 s to get the id, then "waits for the droplet to be active"; find: id 456
        File(root, "scripts/do-build/droplet.py").writeText("""
import os, sys, time
def log(s): open(os.environ["DROPLET_LOG"], "a").write(s + "\n")
cmd = sys.argv[1]
log(" ".join(sys.argv[1:3] if cmd != "create" else ["create"]))
if cmd == "create":
    time.sleep(2); print("123", flush=True); time.sleep(30)
elif cmd == "find":
    print("456")
elif cmd == "delete" and os.environ.get("FAIL_DELETE"):
    sys.exit("delete failed")
""")
        val bin = tmp.newFolder("bin")
        File(bin, "ssh-keygen").writeText("#!/bin/bash\nf=\"\${@: -1}\"; : > \"\$f\"; : > \"\$f.pub\"\n")
        bin.listFiles()!!.forEach { it.setExecutable(true) }
        // setsid: its own process group; the pid (= group id) is written before exec'ing the script.
        val pidFile = File(tmp.root, "pid")
        val p = ProcessBuilder("setsid", "bash", "-c", "echo \$\$ > \"\$PID_FILE\"; exec bash scripts/do-build/$script")
            .directory(root).redirectErrorStream(true)
        p.environment().apply {
            put("HOME", File(tmp.root, "home").apply { mkdirs() }.path); put("PATH", "${bin.path}:${get("PATH")}")
            put("DEADLINE", File(tmp.root, "deadline.py").apply { writeText("") }.path)
            put("DROPLET_LOG", File(tmp.root, "droplet.log").path); put("PID_FILE", pidFile.path); remove("KEEP")
            if (failDelete) put("FAIL_DELETE", "1")
        }
        val proc = p.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (!log.contains("create") && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Thread.sleep(300)
        ProcessBuilder("kill", "-$signal", "--", "-${pidFile.readText().trim()}").start().waitFor()
        var out = ""
        val reader = Thread { out = proc.inputStream.bufferedReader().readText() }.apply { start() }
        assertTrue("script did not end", proc.waitFor(60, TimeUnit.SECONDS))
        reader.join()
        return proc.exitValue() to out
    }

    @Test fun ctrlCDuringCreateDeletesTheIdItPrints() {
        for (script in listOf("run.sh", "app.sh")) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val (code, out) = interrupt(script, "INT")
            assertEquals(out, 130, code)
            assertEquals(script, listOf("create", "delete 123"), log.trim().lines())
            assertTrue(out, out.contains("waiting for it so its droplet can be deleted"))
        }
    }

    @Test fun aFailedDeleteOfARecoveredIdDoesNotClaimADeadline() {
        for (script in listOf("run.sh", "app.sh")) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            val (code, out) = interrupt(script, "INT", failDelete = true)
            assertEquals(out, 130, code)
            assertTrue(out, out.contains("droplet 123 NOT verified deleted and NO deadline is armed"))
            assertTrue(out, !out.contains("deadline stays armed"))
        }
    }

    @Test fun aCreateKilledBeforeItsIdIsFoundByNameAndDeleted() {
        for (script in listOf("run.sh", "app.sh")) {
            tmp.root.listFiles()!!.forEach { it.deleteRecursively() }
            assertEquals(script, 130, interrupt(script, "TERM").first)
            val calls = log.trim().lines()
            assertEquals(script, "create", calls[0])
            assertTrue("$script: $calls", calls[1].startsWith("find gvpndroid-"))
            assertEquals(script, "delete 456", calls[2])
        }
    }
}
