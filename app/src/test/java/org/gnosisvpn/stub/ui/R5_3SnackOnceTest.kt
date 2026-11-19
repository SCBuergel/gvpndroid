package org.gnosisvpn.stub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each showSnack is shown once, also when a recreated activity starts its snackbar effect again. */
class R5_3SnackOnceTest {
    @Test
    fun aShownSnackIsNotReplayedButTheSameTextAgainIs() {
        val ui = UiState("light") {}
        assertNull(ui.takeSnack())
        ui.showSnack("needs permission")
        assertEquals("needs permission", ui.takeSnack())
        assertNull("recreated activity replays the last snack", ui.takeSnack())
        ui.showSnack("needs permission")
        assertEquals("needs permission", ui.takeSnack())
    }

    @Test
    fun aSnackPostedWithoutAHostWaitsForTheNextOne() {
        val ui = UiState("light") {}
        ui.showSnack("first")
        ui.showSnack("second")
        assertEquals("second", ui.takeSnack())
        assertNull(ui.takeSnack())
    }
}
