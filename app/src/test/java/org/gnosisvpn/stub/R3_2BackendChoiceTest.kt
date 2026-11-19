package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** A WireGuard choice survives a new process; backends swap only while the tunnel is down (R3-2). */
class R3_2BackendChoiceTest {
    private fun withDir(body: (File) -> Unit) = Files.createTempDirectory("nb").toFile().let { try { body(it) } finally { it.deleteRecursively() } }
    private fun shouldSetUp(nb: File) = App.shouldSetUpGnosis(usesGnosis = File(nb, "gnosis.properties").exists(), canUseGnosis = true, choseWireGuard = GnosisSetup.choseWireGuard(nb))

    @Test
    fun wireGuardChoiceSurvivesANewProcess() = withDir { nb ->
        assertTrue(shouldSetUp(nb))                                   // fresh install: set up Gnosis
        GnosisSetup.install(nb, "version = 7\n") { "network=x\n".byteInputStream() }
        assertFalse(shouldSetUp(nb))                                  // on Gnosis
        GnosisSetup.disable(nb)                                       // "Use WireGuard"
        assertFalse(File(nb, "gnosis.properties").exists())
        assertFalse(shouldSetUp(nb))                                  // the next process keeps WireGuard
        GnosisSetup.install(nb, "version = 7\n") { "network=x\n".byteInputStream() }   // "Use Gnosis"
        assertFalse(GnosisSetup.choseWireGuard(nb))
    }

    @Test
    fun installKeepsSavedProperties() = withDir { nb ->
        File(nb, "gnosis.properties").writeText("destination=mine\n")
        GnosisSetup.install(nb, "version = 7\n") { error("must not reset gnosis.properties") }
        assertEquals("destination=mine\n", File(nb, "gnosis.properties").readText())
    }

    @Test
    fun cannotSetUpWithoutTheCore() {
        assertFalse(App.shouldSetUpGnosis(usesGnosis = false, canUseGnosis = false, choseWireGuard = false))
    }

    @Test
    fun swapOnlyWhileTheTunnelIsDown() {
        val swapped = Tunnel.State.entries.filter { s -> var ran = false; App.swapWhenDown(s) { ran = true }.also { assertEquals(it, ran) } }
        assertEquals(listOf(Tunnel.State.DISCONNECTED, Tunnel.State.ERROR), swapped)
    }

    @Test
    fun subscriptionRemovesTheListenerFromItsOwnTunnel() {
        class Fake : Tunnel {
            val listeners = mutableListOf<Tunnel.Listener>()
            override val state = Tunnel.State.CONNECTED
            override fun connect() {}
            override fun disconnect() {}
            override fun stats(): Tunnel.Stats? = null
            override fun addListener(listener: Tunnel.Listener) { listeners += listener }
            override fun removeListener(listener: Tunnel.Listener) { listeners -= listener }
        }
        val old = Fake(); val replacement = Fake()
        val l = Tunnel.Listener { _, _ -> }
        val sub = Subscription(old, l)
        replacement.addListener(l)                                    // App.tunnel swapped meanwhile
        sub.cancel()
        assertEquals(emptyList<Tunnel.Listener>(), old.listeners)
        assertEquals(listOf(l), replacement.listeners)
    }
}
