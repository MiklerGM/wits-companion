package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.wits.BrightnessController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brightness step arithmetic, tested without the Android settings provider.
 *
 * The two invariants that matter while driving: a step is a fixed ~20 % of the range, and
 * the result never reaches zero (a black panel in motion) nor exceeds the maximum.
 */
class BrightnessControllerTest {

    @Test
    fun `a step is about 20 percent of the full range`() {
        // 0.2 * 255 = 51.
        assertEquals(153, BrightnessController.nextRaw(102, +BrightnessController.STEP_FRACTION))
        assertEquals(102, BrightnessController.nextRaw(153, -BrightnessController.STEP_FRACTION))
    }

    @Test
    fun `dim never drives the panel to black`() {
        // From anywhere, one dim stays at or above the floor — never 0.
        for (start in 0..BrightnessController.MAX_RAW) {
            val next = BrightnessController.nextRaw(start, -BrightnessController.STEP_FRACTION)
            assertTrue("dim from $start gave $next, below the floor", next >= BrightnessController.MIN_RAW)
        }
    }

    @Test
    fun `repeated dimming settles at the floor, not zero`() {
        var raw = 200
        repeat(20) { raw = BrightnessController.nextRaw(raw, -BrightnessController.STEP_FRACTION) }
        assertEquals(BrightnessController.MIN_RAW, raw)
    }

    @Test
    fun `brighten never exceeds the maximum`() {
        for (start in 0..BrightnessController.MAX_RAW) {
            val next = BrightnessController.nextRaw(start, +BrightnessController.STEP_FRACTION)
            assertTrue("brighten from $start gave $next, above max", next <= BrightnessController.MAX_RAW)
        }
        var raw = 50
        repeat(20) { raw = BrightnessController.nextRaw(raw, +BrightnessController.STEP_FRACTION) }
        assertEquals(BrightnessController.MAX_RAW, raw)
    }

    @Test
    fun `percent maps the raw range to 0 to 100`() {
        assertEquals(0, BrightnessController.rawToPercent(0))
        assertEquals(100, BrightnessController.rawToPercent(BrightnessController.MAX_RAW))
        assertEquals(50, BrightnessController.rawToPercent(128))
        assertEquals(5, BrightnessController.rawToPercent(BrightnessController.MIN_RAW))
    }

    @Test
    fun `dimming then brightening from mid-range returns near the start`() {
        val start = 153
        val down = BrightnessController.nextRaw(start, -BrightnessController.STEP_FRACTION)
        val backUp = BrightnessController.nextRaw(down, +BrightnessController.STEP_FRACTION)
        assertEquals(start, backUp)
    }
}
