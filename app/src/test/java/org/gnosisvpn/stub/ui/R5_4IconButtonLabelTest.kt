package org.gnosisvpn.stub.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * IB names its button with its label for TalkBack (R5-4). The JVM tests have no Compose UI
 * runtime (no Robolectric or compose-ui-test), so this reads IB's source: the IconButton carries
 * the label as contentDescription, and the icon inside stays unnamed (announced once).
 */
class R5_4IconButtonLabelTest {
    private val src = File("src/main/java/org/gnosisvpn/stub/ui/Components.kt").readText()

    @Test fun iconButtonsAreNamedByTheirLabel() {
        val ib = src.substringAfter("fun IB(").substringBefore("\n}\n")
        assertTrue(ib, Regex("""IconButton\([^\n]*\.semantics \{ contentDescription = label \}""").containsMatchIn(ib))
        val ms = src.substringAfter("fun Ms(").substringBefore("\n}\n")
        assertTrue(ms, ms.contains("contentDescription = null"))
    }
}
