package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every file outside app/ that a unit test reads is a declared input of the tests, so a change to it alone reruns them (R7-12). */
class R7_12TestInputsTest {
    @Test fun everyOutsidePathIsADeclaredInput() {
        val declared = Regex(""""\.\./([^"]+)"""").findAll(File("build.gradle.kts").readText()).map { it.groupValues[1] }.toList()
        val used = File("src/test").walk().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> Regex("""File\("\.\./([^"$]+)""").findAll(f.readText()).map { it.groupValues[1].trimEnd('/') } }
            .toSortedSet()
        val missing = used.filter { u -> declared.none { d -> u == d || u.startsWith("$d/") } }
        assertEquals(emptyList<String>(), missing)
    }
}
