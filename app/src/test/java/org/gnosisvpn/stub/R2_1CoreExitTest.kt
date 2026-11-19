package org.gnosisvpn.stub

import org.junit.Assert.assertEquals
import org.junit.Test

/** CoreExit.decide: what GnosisTunnel does when its core exits by itself (R2-1). */
class R2_1CoreExitTest {
    @Test fun aCoreExitWhileConnectingRestartsItABoundedNumberOfTimes() {
        val decisions = (0..CoreExit.MAX_RESTARTS).map { CoreExit.decide(connecting = true, restartsSinceConnected = it) }
        assertEquals(List(CoreExit.MAX_RESTARTS) { CoreExit.RESTART } + CoreExit.GIVE_UP, decisions)
    }

    @Test fun aCoreExitWithNoConnectionWantedGoesIdle() {
        // prestart without a connect, our own stopCore (shutdown), or after a user disconnect
        assertEquals(CoreExit.IDLE, CoreExit.decide(connecting = false, restartsSinceConnected = 0))
        assertEquals(CoreExit.IDLE, CoreExit.decide(connecting = false, restartsSinceConnected = CoreExit.MAX_RESTARTS))
    }
}
