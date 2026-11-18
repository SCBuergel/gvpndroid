package org.gnosisvpn.stub

// DEBUG ONLY. REMOVE BEFORE PRODUCTION, together with the developer log relay it tests.

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream

class DebugLogRelayCoreTest {
    private val DAY = 86_400_000L

    @Test fun sendingEvery10sStaysUnderNtfysDailyLimit() {
        val b = DebugLogRelayCore.Budget()
        var t = 1_000_000L
        var sent = 0
        val firstMinutes = mutableListOf<Long>()
        while (t < 1_000_000L + DAY) {
            b.refill(t)
            if (b.canSend(urgent = false)) { b.spend(); sent++; if (firstMinutes.size < 20) firstMinutes += t }
            t += 10_000
        }
        assertTrue("sent $sent in a day", sent <= 245)
        assertTrue("about one every 6 to 7 min after the start: $sent", sent >= 200)
        // the start goes every 10 s
        assertEquals(10_000L, firstMinutes[1] - firstMinutes[0])
        assertEquals(10_000L, firstMinutes[15] - firstMinutes[14])
    }

    @Test fun problemsMayUseTheReserve() {
        val b = DebugLogRelayCore.Budget(tokens = 5.0, at = 1)
        assertFalse(b.canSend(urgent = false))
        assertTrue(b.canSend(urgent = true))
        b.exhausted()
        assertFalse(b.canSend(urgent = true))
        assertTrue(DebugLogRelayCore.URGENT.containsMatchIn("core WARN connect failed: timeout"))
        assertFalse(DebugLogRelayCore.URGENT.containsMatchIn("core INFO session renewed, 650 MB more"))
    }

    @Test fun packKeepsEveryLineAndFitsNtfy() {
        val rnd = java.util.Random(7)
        val lines = (1..3000).map { i -> "20:4$i  core INFO hopr path ${rnd.nextLong().toString(16)} peer 12D3KooW${rnd.nextLong()} rtt ${rnd.nextInt(900)}" }
        val pieces = DebugLogRelayCore.pack("hdr", lines)
        assertTrue("several pieces: ${pieces.size}", pieces.size > 3)
        assertEquals(lines.size, pieces.sumOf { it.second })
        val back = pieces.flatMap { (z, _) -> GZIPInputStream(z.inputStream()).readBytes().toString(Charsets.UTF_8).trimEnd('\n').split('\n').also { assertEquals("hdr", it[0]) }.drop(1) }
        assertEquals(lines, back)
        val pub = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { X25519PrivateKeyParameters(it, 0).generatePublicKey().encoded }
        pieces.forEach { (z, _) ->
            assertTrue(z.size <= DebugLogRelayCore.MAX_PLAIN)
            assertTrue("ntfy keeps bodies up to 4096 bytes as messages", DebugLogRelayCore.seal(z, pub).toByteArray().size <= 4096)
        }
        val huge = DebugLogRelayCore.pack("hdr", listOf("x".repeat(50_000)))
        assertEquals(1, huge.size)
    }

    @Test fun sealOpensWithTheDevelopersKey() {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey().encoded
        val msg = "hello relay".toByteArray()
        val sealed = DebugLogRelayCore.seal(msg, pub)
        assertTrue(sealed.startsWith("gvpndroid-log-v1 "))
        val blob = Base64.getDecoder().decode(sealed.substringAfter(' '))
        val e = blob.copyOfRange(0, 32); val ct = blob.copyOfRange(32, blob.size)
        val agr = X25519Agreement().apply { init(priv) }
        val shared = ByteArray(32).also { agr.calculateAgreement(X25519PublicKeyParameters(e, 0), it, 0) }
        val k = MessageDigest.getInstance("SHA-256").run { update("gvpndroid-log-v1".toByteArray()); update(shared); update(e); update(pub); digest() }
        val c = ChaCha20Poly1305().apply { init(false, AEADParameters(KeyParameter(k), 128, ByteArray(12), e)) }
        val out = ByteArray(c.getOutputSize(ct.size)); val n = c.processBytes(ct, 0, ct.size, out, 0); c.doFinal(out, n)
        assertEquals("hello relay", String(out.copyOf(msg.size)))
        // a fresh ephemeral key every time
        assertFalse(sealed == DebugLogRelayCore.seal(msg, pub))
    }

    private fun unzip(z: ByteArray) = GZIPInputStream(z.inputStream()).readBytes().toString(Charsets.UTF_8).trimEnd('\n').split('\n')

    @Test fun queueSendsNewLinesOnceAndKeepsUnsentOnes() {
        val q = DebugLogRelayCore.Queue()
        val b = DebugLogRelayCore.Budget(tokens = 25.0, at = 1)
        val got = mutableListOf<String>()
        q.take(listOf("a", "b"), next = 2, lost = 0)
        assertEquals(DebugLogRelayCore.Outcome.SENT, q.flush("h", b) { z, n -> assertEquals(1L, n); got += unzip(z).drop(1); 200 })
        assertEquals(listOf("a", "b"), got); assertTrue(q.pending.isEmpty()); assertEquals(2L, q.cursor)
        assertEquals(DebugLogRelayCore.Outcome.NOTHING, q.flush("h", b) { _, _ -> error("nothing to send") })
        // no connection: the lines stay for the next tick
        q.take(listOf("c"), next = 3, lost = 0)
        assertEquals(DebugLogRelayCore.Outcome.FAILED, q.flush("h", b) { _, _ -> -1 })
        assertEquals(listOf("c"), q.pending.toList())
        assertEquals(DebugLogRelayCore.Outcome.SENT, q.flush("h", b) { z, n -> assertEquals(2L, n); got += unzip(z).drop(1); 200 })
        assertEquals(listOf("a", "b", "c"), got)
    }

    @Test fun partialSendRemovesOnlyTheSentLines() {
        val q = DebugLogRelayCore.Queue()
        val rnd = java.util.Random(3)
        val lines = (1..3000).map { "line $it ${rnd.nextLong()} ${rnd.nextLong()}" }
        q.take(lines, next = 3000, lost = 0)
        val b = DebugLogRelayCore.Budget(tokens = 10.0, at = 1)   // 2 above the reserve: two messages
        val got = mutableListOf<String>()
        assertEquals(DebugLogRelayCore.Outcome.SENT, q.flush("h", b) { z, _ -> got += unzip(z).drop(1); 200 })
        assertEquals(2L, q.seq)
        assertEquals(lines.take(got.size), got)
        assertEquals(lines.drop(got.size), q.pending.toList())
        assertEquals(DebugLogRelayCore.Outcome.BUDGET, q.flush("h", b) { _, _ -> error("over budget") })
    }

    @Test fun lossIsReportedOnceAnd429EmptiesTheBucket() {
        val q = DebugLogRelayCore.Queue(maxPending = 3)
        q.take(listOf("1", "2", "3", "4", "5"), next = 9, lost = 4)   // 4 lost in the ring, 2 over the cap
        assertEquals(6L, q.dropped); assertEquals(listOf("3", "4", "5"), q.pending.toList())
        val b = DebugLogRelayCore.Budget(tokens = 25.0, at = 1)
        assertEquals(DebugLogRelayCore.Outcome.LIMITED, q.flush("h", b) { _, _ -> 429 })
        assertFalse(b.canSend(urgent = true)); assertEquals(6L, q.dropped)
        val b2 = DebugLogRelayCore.Budget(tokens = 25.0, at = 1)
        val got = mutableListOf<String>()
        q.flush("h", b2) { z, _ -> got += unzip(z).drop(1); 200 }
        assertEquals(listOf("[6 lines were not sent: log ring or relay buffer full]", "3", "4", "5"), got)
        assertEquals(0L, q.dropped)
        q.take(listOf("x"), next = 10, lost = 0); q.reset(now = 12)
        assertTrue(q.pending.isEmpty()); assertEquals(12L, q.cursor)
    }

    @Test fun theRelaysOwnFailuresAreNotUrgent() {
        val q = DebugLogRelayCore.Queue()
        q.take(listOf("10:00:00  ${DebugLogRelayCore.OWN} sending failed (no connection); keeping the lines"), next = 1, lost = 0)
        val low = DebugLogRelayCore.Budget(tokens = 5.0, at = 1)   // only the reserve left
        assertEquals(DebugLogRelayCore.Outcome.BUDGET, q.flush("h", low) { _, _ -> 200 })
        q.take(listOf("10:00:01  core ERROR connect failed"), next = 2, lost = 0)
        assertEquals(DebugLogRelayCore.Outcome.SENT, q.flush("h", low) { _, _ -> 200 })
    }
}
