package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** TunSlot.close drops the tun fds queued for the core, under the lock tuns are handed under (R1-4). */
class R1_4TunReleaseTest {
    private class FakeTun : Closeable { override fun close() {} }

    @Test fun closeReleasesUnderTheLockAndHandOverDoesNot() {
        val released = mutableListOf<Boolean>()
        lateinit var slot: TunSlot<FakeTun>
        slot = TunSlot(released = { released += Thread.holdsLock(slot) }) { true }
        assertTrue(slot.install(FakeTun(), "a") {})
        assertEquals(TunSlot.Answer.HANDED, slot.handSame("a") {})
        assertTrue(slot.install(FakeTun(), "b") {})   // make-before-break swap: nothing dropped
        assertEquals(emptyList<Boolean>(), released)
        slot.close()
        assertEquals(listOf(true), released)
    }
}
