package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A core start without network is retried while the VPN is wanted, not ended in ERROR (R2-4). */
class R2_4StartRetryTest {
    @Volatile private var wanted = true

    @Test fun onlyTheResolveErrorIsTransient() {
        assertTrue(startCoreError("resolve blokli.example: failed to lookup address information") is NoNetwork)
        assertFalse(startCoreError("config: bad toml") is NoNetwork)
        assertFalse(startCoreError("core already running") is NoNetwork)
    }

    @Test fun failsTwiceWithoutNetworkThenStarts() {
        var tries = 0; var waits = 0
        val ok = StartRetry({ wanted }, firstMs = 5, maxMs = 20).run({ waits++ }) {
            if (++tries <= 2) throw startCoreError("resolve blokli.example: no network")
        }
        assertTrue(ok)
        assertEquals(3, tries)
        assertEquals(2, waits)
    }

    @Test fun aDisconnectDuringTheWaitStopsRetrying() {
        val retry = StartRetry({ wanted }, firstMs = 60_000)
        var tries = 0
        var result: Boolean? = null
        val waiting = java.util.concurrent.CountDownLatch(1)
        val t = Thread { result = retry.run({ waiting.countDown() }) { tries++; throw NoNetwork("startCore: resolve x: no network") } }.apply { start() }
        assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS))
        wanted = false; retry.wake()   // like disconnect()
        t.join(2_000)
        assertFalse(t.isAlive)
        assertEquals(false, result)
        assertEquals(1, tries)
    }

    @Test fun otherErrorsPassThrough() {
        val e = runCatching { StartRetry({ wanted }).run({}) { throw startCoreError("config: bad toml") } }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }
}
