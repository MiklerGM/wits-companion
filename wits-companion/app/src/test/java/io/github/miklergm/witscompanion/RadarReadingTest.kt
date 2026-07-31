package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.carstate.RadarReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding of the vendor `can.radar` string.
 *
 * The decoder is what a forward-parking display would rest on, and its whole purpose is to
 * make "is any front sensor reporting?" answerable at a glance while parking forward. A
 * wrong split or a swallowed malformed field would quietly hide the very signal we are
 * trying to observe.
 */
class RadarReadingTest {

    @Test
    fun `parses the documented eight-value form`() {
        val r = RadarReading.parse("2:0:0:4:0:0:0:0")!!
        assertEquals(listOf(2, 0, 0, 4, 0, 0, 0, 0), r.values)
        assertEquals(listOf(2, 0, 0, 4), r.front)
        assertEquals(listOf(0, 0, 0, 0), r.rear)
    }

    @Test
    fun `front activity is detected, rear all-clear is not a false positive`() {
        val r = RadarReading.parse("0:3:0:0:0:0:0:0")!!
        assertTrue("a non-zero front sensor is active", r.anyFrontActive)
        assertFalse("rear is all zero", r.anyRearActive)
        assertEquals(3, r.frontClosest)
    }

    @Test
    fun `rear-only reading does not read as front`() {
        val r = RadarReading.parse("0:0:0:0:5:0:0:1")!!
        assertFalse(r.anyFrontActive)
        assertTrue(r.anyRearActive)
        assertNull(r.frontClosest)
    }

    @Test
    fun `blank or null yields no reading`() {
        assertNull(RadarReading.parse(null))
        assertNull(RadarReading.parse("   "))
    }

    @Test
    fun `a malformed field becomes a null sensor, not a lost reading`() {
        val r = RadarReading.parse("2:x:0:4")!!
        assertEquals(2, r.frontLeft)
        assertNull("the bad field is null", r.frontMidLeft)
        assertEquals(4, r.frontRight)
        // Missing trailing fields are null, not zero — absence is not "clear".
        assertNull(r.rearLeft)
    }

    @Test
    fun `all-zero reads as empty`() {
        assertTrue(RadarReading.parse("0:0:0:0:0:0:0:0")!!.isEmpty())
        assertFalse(RadarReading.parse("0:0:0:1:0:0:0:0")!!.isEmpty())
    }
}
