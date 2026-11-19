package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** scripts/gnosis-emulator.sh e2e must not run network replies as shell code (finding R7-1). */
class GnosisEmulatorEvalTest {
    private val script = File("../scripts/gnosis-emulator.sh").readLines()

    private fun line(start: String) = script.single { it.trim().startsWith(start) }.trim()

    /** Runs [body] in sh after the script's ipaddr() and check() definitions; returns stdout + stderr. */
    private fun sh(body: String, vararg env: Pair<String, String>): String {
        val pb = ProcessBuilder("sh", "-c", "fails=0\n${line("ipaddr()")}\n${line("check()")}\n$body").redirectErrorStream(true)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    @Test
    fun ipaddrKeepsOnlyAnIpLiteral() {
        fun ipaddr(input: String) = sh("printf '%s' \"\$IN\" | ipaddr", "IN" to input).trim()
        assertEquals("1.2.3.4", ipaddr("HTTP/1.0 200 OK\r\n\r\n1.2.3.4"))
        assertEquals("2001:db8::1", ipaddr("2001:db8::1\n"))
        assertEquals("", ipaddr("1.2.3.4\$(echo INJECTED)"))
        assertEquals("", ipaddr(""))
    }

    @Test
    fun publicIpCheckTreatsTheReplyAsData() {
        val ipCheck = line("check \"public IP")
        val injected = sh(ipCheck, "ip" to "1.2.3.4\$(echo INJECTED)", "host_ip" to "5.6.7.8")
        assertFalse(injected, injected.contains("INJECTED"))
        assertTrue(injected, injected.startsWith("PASS"))
        assertTrue(sh(ipCheck, "ip" to "", "host_ip" to "5.6.7.8").startsWith("FAIL"))
        assertTrue(sh(ipCheck, "ip" to "5.6.7.8", "host_ip" to "5.6.7.8").startsWith("FAIL"))
        // this machine's lookup failed: nothing was compared, so no PASS (R7-7)
        assertTrue(sh(ipCheck, "ip" to "1.2.3.4", "host_ip" to "").startsWith("FAIL"))
    }

    @Test
    fun noCheckSplicesAVariableIntoItsEvalString() {
        // check NAME "...$x..." expands $x before eval parses it; '...' leaves it to eval as data.
        val spliced = script.filter { Regex("""^\s*check "[^"]*" ".*\$""").containsMatchIn(it) }
        assertEquals(emptyList<String>(), spliced)
    }
}
