package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** TunSlot: a tun request after the VPN stopped being wanted leaves no tun open (R2-2). */
class R2_2TunSlotTest {
    private class FakeTun(val name: String) : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    private var wanted = true
    private val slot = TunSlot<FakeTun> { wanted }
    private val handed = mutableListOf<String>()

    @Test fun aTunBuiltWhileTheUserDisconnectedIsClosedAndNotHanded() {
        assertEquals(TunSlot.Answer.BUILD_NEW, slot.handSame("a") { handed += it.name })
        val fresh = FakeTun("t1")   // establish() runs outside the lock; the user disconnects meanwhile
        wanted = false; slot.close()
        assertFalse(slot.install(fresh, "a") { handed += it.name })
        assertTrue(fresh.closed)
        assertEquals(emptyList<String>(), handed)
        assertEquals(TunSlot.Answer.REFUSED, slot.handSame("a") { handed += it.name })
    }

    @Test fun inPlaceReconnectHandsTheSameTunAndAChangeSwapsMakeBeforeBreak() {
        val t1 = FakeTun("t1")
        assertTrue(slot.install(t1, "a") { handed += it.name })
        assertEquals(TunSlot.Answer.HANDED, slot.handSame("a") { handed += it.name })
        assertFalse(t1.closed)
        assertEquals(TunSlot.Answer.BUILD_NEW, slot.handSame("b") { handed += it.name })
        val t2 = FakeTun("t2")
        assertTrue(slot.install(t2, "b") { assertTrue(t1.closed); handed += it.name })
        assertEquals(listOf("t1", "t1", "t2"), handed)
        wanted = false; slot.close()
        assertTrue(t2.closed)
    }
}
