package org.gnosisvpn.stub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ConnectIntent: a connect during a pending disconnect connects after it, and the last action wins (R2-3). */
class R2_3ConnectIntentTest {
    private val i = ConnectIntent()

    @Test fun connectDuringADisconnectIsWantedOnlyOnceItFinished() {
        i.connect(); assertTrue(i.wanted)
        i.disconnect(); assertFalse(i.wanted)
        i.connect()
        assertFalse("the old connection's tun requests stay refused", i.wanted)
        assertFalse("the disconnect task leaves CONNECTING", i.disconnectDone())
        assertTrue(i.wanted)
    }

    @Test fun aDisconnectAfterThePendingConnectWins() {
        i.disconnect(); i.connect(); i.disconnect()
        assertTrue(i.disconnectDone()); assertTrue(i.disconnectDone())
        assertFalse(i.wanted)
    }

    @Test fun aConnectBetweenTwoDisconnectsWaitsForBoth() {
        i.disconnect(); i.disconnect(); i.connect()
        assertFalse(i.disconnectDone()); assertFalse(i.wanted)
        assertFalse(i.disconnectDone()); assertTrue(i.wanted)
    }

    @Test fun clearingWantedDropsAPendingConnect() {
        i.disconnect(); i.connect(); i.wanted = false   // giveUp or shutdown meanwhile
        assertTrue(i.disconnectDone()); assertFalse(i.wanted)
    }

    @Test fun aPlainDisconnectEndsIdle() {
        i.connect(); i.disconnect()
        assertTrue(i.disconnectDone()); assertFalse(i.wanted)
    }
}
