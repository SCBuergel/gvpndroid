package org.gnosisvpn.stub

// =================================================================================================
//  DEBUG BUILDS ONLY. REMOVE BEFORE PRODUCTION.
//  Part of the developer log relay (see DebugLogRelay.kt in this source set). This file lives in
//  app/src/debug (its splash notice in app/src/debugRelay), so release builds do not contain it; app/src/release has a no-op stub.
// =================================================================================================

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlin.math.min

/** The relay's pure parts (no Android): sending budget, packing and sealing. Unit-tested. */
object DebugLogRelayCore {
    const val TAG = "gvpndroid-log-v1"

    /**
     * ntfy.sh allows an anonymous sender 250 messages a day per IP address. With the Gnosis
     * backend the app is excluded from its own VPN (GnosisVpnService: addDisallowedApplication),
     * so the relay posts from the device's own network and IP, also while the VPN is on, and that
     * IP's budget is shared with nothing else of ours. A token bucket keeps the relay under it: it holds up to [CAP]
     * messages and refills [PER_DAY] a day, so no rolling day gets more than CAP + PER_DAY = 242. A full
     * bucket covers the app's start window (every 30 s for 15 min).
     * Ordinary log lines leave [RESERVE] in the bucket. Lines that look like a problem
     * ([URGENT]) may use the reserve.
     */
    class Budget(var tokens: Double = CAP, var at: Long = 0) {
        fun refill(now: Long) {
            if (at > 0 && now > at) tokens = min(CAP, tokens + (now - at) / 86_400_000.0 * PER_DAY)
            at = now
        }
        fun canSend(urgent: Boolean) = tokens >= 1.0 + if (urgent) 0.0 else RESERVE
        fun spend() { tokens = maxOf(0.0, tokens - 1.0) }
        /** ntfy answered 429: its own count for this IP is used up, so the bucket is empty too. */
        fun exhausted() { tokens = 0.0 }

        companion object {
            const val CAP = 32.0
            const val PER_DAY = 210.0
            const val RESERVE = 2.0
        }
    }

    /** Marks the relay's own log lines; they never make a tick urgent (a failing relay must not spend the reserve). */
    const val OWN = "debug log relay:"

    val URGENT = Regex("(?i)\\b(error|errors|failed|failure|panic|crash|crashed|fatal|stall|stalled|unrecoverable|refused|denied|exception)\\b")

    /** Longest plaintext (gzip output) per message: sealed and base64-encoded it stays under ntfy's 4 KB message limit. */
    const val MAX_PLAIN = 2900
    /** Largest gzip'd backlog sent as one attachment: sealed and base64-encoded it stays under ntfy's 2 MB per file. */
    const val MAX_ATTACHMENT_PLAIN = 1_400_000
    private const val MAX_LINE = 2500

    /**
     * Splits [lines] into gzip pieces of at most [maxPlain] bytes, each starting with [header].
     * Returns each piece with the number of lines it holds, in order. A very long line is cut.
     */
    fun pack(header: String, lines: List<String>, maxPlain: Int = MAX_PLAIN): List<Pair<ByteArray, Int>> {
        val out = mutableListOf<Pair<ByteArray, Int>>()
        var i = 0
        while (i < lines.size) {
            // Grow the piece while it fits (log text compresses about 5 to 10 times).
            var n = 1
            var best = gzip(text(header, lines, i, 1))
            while (i + n < lines.size) {
                val next = min(lines.size - i, n * 2)
                val z = gzip(text(header, lines, i, next))
                if (z.size > maxPlain) {
                    // Binary search between n (fits) and next (too big).
                    var lo = n; var hi = next
                    while (hi - lo > 1) {
                        val mid = (lo + hi) / 2
                        val zm = gzip(text(header, lines, i, mid))
                        if (zm.size <= maxPlain) { lo = mid; best = zm } else hi = mid
                    }
                    n = lo
                    break
                }
                n = next; best = z
            }
            out += best to n
            i += n
        }
        return out
    }

    private fun text(header: String, lines: List<String>, from: Int, n: Int): String =
        buildString {
            append(header).append('\n')
            for (k in from until from + n) append(lines[k].let { if (it.length > MAX_LINE) it.take(MAX_LINE) + " [cut]" else it }).append('\n')
        }

    fun gzip(s: String): ByteArray {
        val b = ByteArrayOutputStream()
        GZIPOutputStream(b).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        return b.toByteArray()
    }

    /**
     * The start window: for [startMin] minutes after [startedAt] the relay sends at most every
     * [intervalSec] seconds, unless a line looks like a problem ([urgent]); afterwards on every tick
     * (the budget then decides).
     */
    fun mayFlush(now: Long, startedAt: Long, lastSentAt: Long, urgent: Boolean, startMin: Long = 15, intervalSec: Long = 30): Boolean =
        urgent || now - startedAt >= startMin * 60_000 || now - lastSentAt >= intervalSec * 1000

    /** What one [Queue.flush] did. */
    enum class Outcome { NOTHING, SENT, BUDGET, LIMITED, FAILED }

    /**
     * The relay's state between ticks: which log lines are still to be sent and how many were lost.
     * Plain Kotlin, so the send logic is unit-tested; DebugLogRelay feeds it from LogBuffer and
     * gives it the HTTP POST.
     */
    class Queue(private val maxPending: Int = 6000, private val maxAttachment: Int = MAX_ATTACHMENT_PLAIN) {
        /** LogBuffer line count up to which lines have been taken. */
        var cursor = 0L
            private set
        val pending = ArrayDeque<String>()
        /** Lines lost before they were sent (log ring overran, or this queue was full). */
        var dropped = 0L
            private set
        /** Messages sent so far (numbered in the title). */
        var seq = 0L
            private set

        /** Takes new lines: [fresh] from LogBuffer.since(cursor), which reports [next] and [lost]. */
        fun take(fresh: List<String>, next: Long, lost: Long) {
            cursor = next
            dropped += lost
            pending.addAll(fresh)
            while (pending.size > maxPending) { pending.removeFirst(); dropped++ }
        }

        /** A waiting line looks like a problem (the relay's own lines excluded): it may go out at once. */
        fun hasUrgent(): Boolean = pending.any { !it.contains(OWN) && URGENT.containsMatchIn(it) }

        /** Switched off: forget everything up to [now] (LogBuffer's count); nothing old is sent later. */
        fun reset(now: Long) { pending.clear(); dropped = 0; cursor = now }

        /**
         * Sends what the [budget] allows, oldest lines first, one sealed message per piece. [send]
         * gets the gzip'd piece and its message number and returns the HTTP status, or -1 without
         * a connection. Only lines of pieces that were accepted leave the queue.
         */
        fun flush(header: String, budget: Budget, send: (ByteArray, Long, Boolean) -> Int): Outcome {
            if (pending.isEmpty() && dropped == 0L) return Outcome.NOTHING
            val urgent = hasUrgent()
            // The loss note is a line of its own, so it goes out once, in the first piece.
            val note = if (dropped > 0) listOf("[$dropped lines were not sent: log ring or relay buffer full]") else emptyList()
            val lines = note + pending.toList()
            val pieces = pack(header, lines)
            // More than one message's worth: everything in ONE attachment (ntfy keeps bodies over
            // 4 KB as an attachment, up to 2 MB, and counts it as one message), so a backlog never
            // trails behind the budget and an incident arrives within one tick.
            if (pieces.size > 1) {
                val all = gzip(text(header, lines, 0, lines.size))
                if (all.size <= maxAttachment) {
                    if (!budget.canSend(urgent)) return Outcome.BUDGET
                    return when (send(all, seq + 1, true)) {
                        in 200..299 -> { budget.spend(); seq++; dropped = 0; pending.clear(); Outcome.SENT }
                        429 -> { budget.exhausted(); Outcome.LIMITED }
                        else -> Outcome.FAILED
                    }
                }
            }
            var sent = false
            for ((plain, n) in pieces) {
                if (!budget.canSend(urgent)) return if (sent) Outcome.SENT else Outcome.BUDGET
                when (send(plain, seq + 1, false)) {
                    in 200..299 -> {
                        budget.spend(); seq++; sent = true
                        var k = n
                        if (dropped > 0 && note.isNotEmpty()) { dropped = 0; k-- }
                        repeat(minOf(k, pending.size)) { pending.removeFirst() }
                    }
                    429 -> { budget.exhausted(); return Outcome.LIMITED }
                    else -> return if (sent) Outcome.SENT else Outcome.FAILED
                }
            }
            return Outcome.SENT
        }
    }

    /**
     * Seals [plain] to the developer's X25519 public key [recipient] (32 bytes). This is the same
     * construction as gvpndows' identity backup, with its own tag:
     * "gvpndroid-log-v1 " + base64(E || C), where E is a fresh X25519 public key,
     * k = SHA-256(TAG || X25519(e, P) || E || P), and C = ChaCha20-Poly1305(k, nonce 0, aad E).
     * The key k is new for every message, so the fixed nonce is safe.
     * scripts/relay-read.py opens it.
     */
    fun seal(plain: ByteArray, recipient: ByteArray, rnd: SecureRandom = SecureRandom()): String {
        require(recipient.size == 32) { "recipient key must be 32 bytes" }
        val e = X25519PrivateKeyParameters(rnd)
        val big = e.generatePublicKey().encoded
        val agr = X25519Agreement().apply { init(e) }
        val shared = ByteArray(agr.agreementSize)
        agr.calculateAgreement(X25519PublicKeyParameters(recipient, 0), shared, 0)
        val k = MessageDigest.getInstance("SHA-256").run {
            update(TAG.toByteArray()); update(shared); update(big); update(recipient); digest()
        }
        val c = ChaCha20Poly1305().apply { init(true, AEADParameters(KeyParameter(k), 128, ByteArray(12), big)) }
        val ct = ByteArray(c.getOutputSize(plain.size))
        val n = c.processBytes(plain, 0, plain.size, ct, 0)
        c.doFinal(ct, n)
        return "$TAG " + Base64.getEncoder().encodeToString(big + ct)
    }
}
