package org.gnosisvpn.stub.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tile/notification connect is acted on once, never replayed by a recreated screen (finding R5-2). */
class OneShotConnectTest {
    private var t = 0L
    private val shot = OneShot(30_000) { t }

    @Test
    fun takenOnceAfterArming() {
        assertFalse(shot.take())                  // a plain launch: no connect
        shot.arm(); t += 500
        assertTrue(shot.take())                   // the launch the tile started
        assertFalse(shot.take())                  // rotation or recreation: not again
    }

    @Test
    fun staleRequestIsDropped() {
        shot.arm(); t += 30_001
        assertFalse(shot.take())                  // the screen never came up in time
        assertFalse(shot.take())
    }
}
