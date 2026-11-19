package org.gnosisvpn.stub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The tun stays open while it is handed to the core: tunReadyFor borrows its raw fd (R1-3). */
class R1_3TunHandoverTest {
    private class FakeTun : Closeable {
        @Volatile var closed = false
        override fun close() { closed = true }
    }

    private fun closeWaitsForHand(handOver: (TunSlot<FakeTun>, FakeTun, (FakeTun) -> Unit) -> Unit) {
        val slot = TunSlot<FakeTun> { true }
        val tun = FakeTun()
        val inHand = CountDownLatch(1); val release = CountDownLatch(1)
        var closedDuringHand = true
        val handing = Thread {
            handOver(slot, tun) { t -> inHand.countDown(); release.await(5, TimeUnit.SECONDS); closedDuringHand = t.closed }
        }.apply { start() }
        assertTrue(inHand.await(5, TimeUnit.SECONDS))
        val closing = Thread { slot.close() }.apply { start() }   // disconnect on the worker
        closing.join(300)
        assertTrue("close must wait for the hand-over", closing.isAlive)
        release.countDown(); handing.join(5000); closing.join(5000)
        assertFalse(closedDuringHand)
        assertTrue(tun.closed)
    }

    @Test fun newTun() = closeWaitsForHand { slot, tun, hand -> slot.install(tun, "a", hand) }

    @Test fun inPlaceReconnect() = closeWaitsForHand { slot, tun, hand ->
        slot.install(tun, "a") {}
        slot.handSame("a", hand)
    }
}
