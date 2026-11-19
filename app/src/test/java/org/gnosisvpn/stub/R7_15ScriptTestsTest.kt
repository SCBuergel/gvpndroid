package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The scripts' own regression tests run in the gate (R7-15): relay-read.py's (R6-1, R6-2, loopback
 * servers only) and await_job's. test-await-id.sh (~35 s) stays a manual check (README).
 * HOME and GVPNDROID_SECRETS point at an empty folder, so no secret can be read.
 */
class R7_15ScriptTestsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(File("..")).redirectErrorStream(true)
        p.environment().apply {
            put("HOME", tmp.root.path); put("GVPNDROID_SECRETS", tmp.newFolder("secrets").path)
            listOf("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY").forEach { remove(it) }
        }
        val proc = p.start()
        val out = StringBuilder()
        val reader = Thread { out.append(proc.inputStream.bufferedReader().readText()) }.apply { start() }
        val done = proc.waitFor(120, TimeUnit.SECONDS)
        if (!done) proc.destroyForcibly()
        reader.join(5_000)
        assertEquals("${cmd.joinToString(" ")} (timed out: ${!done})\n$out", 0, if (done) proc.exitValue() else -1)
    }

    // -I: no user site or PYTHON* variables; -B: no bytecode written into scripts/.
    @Test fun relayAttachmentTests() = run("python3", "-I", "-B", "scripts/test_r6_1_relay_attachment.py")

    @Test fun relayFollowTests() = run("python3", "-I", "-B", "scripts/test_r6_2_relay_follow.py")

    @Test fun awaitJobTests() = run("bash", "scripts/do-build/test-await-job.sh")
}
