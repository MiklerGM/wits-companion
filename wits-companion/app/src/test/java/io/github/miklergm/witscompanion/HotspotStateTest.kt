package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.wits.HotspotController
import io.github.miklergm.witscompanion.wits.HotspotController.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AP-state code → state mapping, the one piece of the hotspot controller that is pure
 * (the rest is reflection into the framework and can only run on a device). A wrong mapping
 * would show the driver "off" while the hotspot is on, or leave the toggle enabled during a
 * transition.
 */
class HotspotStateTest {

    @Test
    fun `maps the AOSP AP-state codes`() {
        assertEquals(State.ON, HotspotController.mapApState(13))       // ENABLED
        assertEquals(State.OFF, HotspotController.mapApState(11))      // DISABLED
        assertEquals(State.TURNING_ON, HotspotController.mapApState(12))  // ENABLING
        assertEquals(State.TURNING_OFF, HotspotController.mapApState(10)) // DISABLING
        assertEquals(State.FAILED, HotspotController.mapApState(14))   // FAILED
    }

    @Test
    fun `an unknown code is not silently treated as off`() {
        assertEquals(State.UNKNOWN, HotspotController.mapApState(-1))
        assertEquals(State.UNKNOWN, HotspotController.mapApState(0))
        assertEquals(State.UNKNOWN, HotspotController.mapApState(99))
    }
}
