package org.gnosisvpn.stub

import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescribeConfigTest {
    @Test
    fun summaryShowsEndpointButNoKeys() {
        // Keys are generated at test time so that no key material is in the repository.
        val phone = KeyPair()
        val server = KeyPair()
        val text = """
            [Interface]
            PrivateKey = ${phone.privateKey.toBase64()}
            Address = 10.0.0.2/32
            DNS = 9.9.9.9

            [Peer]
            PublicKey = ${server.publicKey.toBase64()}
            Endpoint = 192.0.2.1:51820
            AllowedIPs = 0.0.0.0/0
        """.trimIndent()

        val summary = describeConfig(Config.parse(text.byteInputStream()))

        assertTrue(summary, summary.contains("192.0.2.1:51820"))
        assertTrue(summary, summary.contains("10.0.0.2/32"))
        assertFalse("private key leaked", summary.contains(phone.privateKey.toBase64()))
        assertFalse("private key leaked (hex)", summary.contains(phone.privateKey.toHex()))
        assertFalse("public key in summary", summary.contains(phone.publicKey.toBase64()))
    }
}
