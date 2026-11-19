package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** fetch_app_results (scripts/do-build/lib.sh) with a local fake for ssh_: brings back what exists. */
class R7_3AppResultsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo by lazy { tmp.newFolder("repo") }
    private val droplet by lazy { tmp.newFolder("droplet") }

    /** Runs fetch_app_results in [repo]; the fake ssh_ runs the remote command with /root under [droplet]. */
    private fun fetch(sshFails: Boolean = false): Int {
        val ssh = if (sshFails) "ssh_() { return 255; }" else "ssh_() { bash -c \"\${1//\\/root\\//\$DROPLET/root/}\"; }"
        val script = "set -euo pipefail; . \"\$LIB\"; $ssh; mkdir -p w; fetch_app_results w"
        val p = ProcessBuilder("bash", "-c", script).directory(repo).redirectErrorStream(true)
        p.environment()["LIB"] = File("../scripts/do-build/lib.sh").absolutePath
        p.environment()["DROPLET"] = droplet.path
        val proc = p.start()
        proc.inputStream.readBytes()
        return proc.waitFor()
    }

    private fun remote(path: String, text: String) = File(droplet, "root/src/app/build/$path").apply { parentFile!!.mkdirs() }.writeText(text)
    private fun local(path: String) = File(repo, path)

    @Test
    fun apkAndLintReportComeBackWithoutTestResults() {
        remote("outputs/apk/debug/app-debug.apk", "apk")
        remote("reports/lint-results-debug.html", "lint")
        local("app/build/remote/test-results/stale").apply { parentFile!!.mkdirs() }.writeText("old")
        assertEquals(0, fetch())
        assertEquals("apk", local("app/build/outputs/apk/debug/app-debug.apk").readText())
        assertEquals("lint", local("app/build/remote/reports/lint-results-debug.html").readText())
        assertFalse("stale test results kept", local("app/build/remote/test-results").exists())
    }

    @Test
    fun noBuildDirectoryIsNotAnError() {
        assertEquals(0, fetch())
        assertFalse(local("app/build/outputs/apk/debug/app-debug.apk").exists())
    }

    @Test
    fun failedConnectionFails() {
        remote("reports/x", "x")
        assertNotEquals(0, fetch(sshFails = true))
        assertTrue(local("w").isDirectory)
    }
}
