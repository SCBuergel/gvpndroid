package org.gnosisvpn.stub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner rule (2026-11-23): every published APK sends its log to the developer, so its file name,
 * the release title and the first line of the notes must say "debug" and "logging". Downloading
 * one must never be possible without seeing that.
 */
class ReleaseNamingTest {
    private val wf = File("../.github/workflows/release.yml").readText()

    @Test fun theApkFileNameSaysDebugAndLogging() {
        val names = Regex("""out=(.+\.apk)""").findAll(wf).map { it.groupValues[1] }.toList()
        assertTrue("release.yml must set the APK name with out=...", names.isNotEmpty())
        names.forEach { assertTrue("APK name $it must end in -debug-logging.apk", it.endsWith("-debug-logging.apk")) }
        assertFalse("no other .apk may be published", Regex("""gh release create[^\n]*\.apk""").containsMatchIn(wf))
    }

    @Test fun theReleaseTitleAndNotesSayLoggingIsOn() {
        val create = wf.lines().first { it.contains("gh release create") }
        assertTrue("release title must say logging is on: $create", create.contains("debug build, logging on"))
        assertTrue("the notes must open with the logging warning", wf.contains("**DEBUG BUILD WITH LOGGING ON.**"))
    }
}
