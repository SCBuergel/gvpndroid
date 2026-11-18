package org.gnosisvpn.stub

// =================================================================================================
//  DEBUG BUILDS ONLY. REMOVE BEFORE PRODUCTION.
//  Part of the developer log relay (see DebugLogRelay.kt in this source set). This file lives in
//  app/src/debug, so release builds do not contain it; app/src/release has a no-op stub.
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
     * ntfy.sh allows an anonymous sender 250 messages a day per IP address. With the VPN on, that
     * IP is the exit's. A token bucket keeps the relay under that limit: it holds up to [CAP]
     * messages and refills [PER_DAY] a day, so no rolling day gets more than CAP + PER_DAY = 245.
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
            const val CAP = 25.0
            const val PER_DAY = 220.0
            const val RESERVE = 8.0
        }
    }

    val URGENT = Regex("(?i)\\b(error|errors|failed|failure|panic|crash|crashed|fatal|stall|stalled|unrecoverable|refused|denied|exception)\\b")

    /** Longest plaintext (gzip output) per message: sealed and base64-encoded it stays under ntfy's 4 KB message limit. */
    const val MAX_PLAIN = 2900
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
