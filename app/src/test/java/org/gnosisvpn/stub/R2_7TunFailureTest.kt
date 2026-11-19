package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed tun request keeps the attempt alive while the core retries, unless retrying cannot help (R2-7). */
class R2_7TunFailureTest {
    @Test fun transientFailuresKeepConnecting() {
        for (e in listOf(IllegalStateException("tunReadyFor: core gone"), java.util.concurrent.TimeoutException("service did not start"),
                IllegalArgumentException("bad address")))
            assertTrue(e.toString(), TunFailure.of(e).keepsConnecting)
    }

    @Test fun noVpnPermissionEndsTheAttempt() {
        assertEquals(TunFailure.NO_PERMISSION, TunFailure.of(NoVpnPermission()))
        assertFalse(TunFailure.of(NoVpnPermission()).keepsConnecting)
    }
}
