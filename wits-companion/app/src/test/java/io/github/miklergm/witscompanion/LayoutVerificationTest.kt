package io.github.miklergm.witscompanion

import android.graphics.Rect
import io.github.miklergm.witscompanion.layout.ExpectedTile
import io.github.miklergm.witscompanion.layout.LayoutVerification
import io.github.miklergm.witscompanion.wits.PrivilegedWindowController.TaskSnapshot
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whether the screen matches what an apply intended.
 *
 * The case that motivated these: on 2026-08-21 the verifier logged `ok, tiles: 2` while Maps
 * covered the entire panel. It compared centres only, and a full-display window centres close
 * enough to a 65 % left tile to pass. A verifier that cannot see a failure cannot repair it —
 * so it declared the layout good and stopped trying, and re-applying never helped.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutVerificationTest {

    private val maps = "com.google.android.apps.maps"

    /** The Cockpit's left tile on this unit: a 65/35 split under a 99 px status bar. */
    private val leftTile = ExpectedTile(maps, Rect(0, 99, 1560, 900))

    private fun task(
        pkg: String = maps,
        bounds: Rect = Rect(0, 99, 1560, 900),
        mode: Int = WitsWindowMode.FREEFORM,
        visible: Boolean = true,
    ) = TaskSnapshot(taskId = 1, packageName = pkg, windowingMode = mode, visible = visible, bounds = bounds)

    // ------------------------------------------------------- the observed bug

    @Test
    fun `a window covering the whole display is misplaced, not ok`() {
        // Exactly what was on screen: Maps freeform at full display over the panel.
        //   expected centre 780, actual centre 1200, slack 780 -> the centre test passed.
        // Only the size check catches it.
        val fullDisplay = task(bounds = Rect(0, 0, 2400, 900))
        assertTrue(
            "a full-display window must not be reported as a correctly placed tile",
            LayoutVerification.misplaced(leftTile, listOf(fullDisplay)),
        )
    }

    @Test
    fun `the panel tile is not fooled by a full-display window either`() {
        // The other half of the same screen: the panel is the narrower tile, so a full-display
        // window is even further from it.
        val panel = ExpectedTile("io.github.miklergm.witscompanion", Rect(1560, 99, 2400, 900))
        val full = task(pkg = "io.github.miklergm.witscompanion", bounds = Rect(0, 0, 2400, 900))
        assertTrue(LayoutVerification.misplaced(panel, listOf(full)))
    }

    // ------------------------------------------------------------ still passes

    @Test
    fun `a correctly placed tile is accepted`() {
        assertFalse(LayoutVerification.misplaced(leftTile, listOf(task())))
    }

    @Test
    fun `rounding and inset drift are tolerated`() {
        // The ROM rounds, and the status-bar inset moves the top edge. None of that should
        // trigger a re-assert: a verifier that fires on noise would fight the vendor stack.
        listOf(
            Rect(0, 99, 1560, 900),      // exact
            Rect(0, 0, 1558, 900),       // a couple of pixels narrow, no inset
            Rect(2, 99, 1562, 898),      // shifted a little
            Rect(0, 99, 1480, 900),      // 5 % narrow
        ).forEach { b ->
            assertFalse("$b should be accepted", LayoutVerification.misplaced(leftTile, listOf(task(bounds = b))))
        }
    }

    // ------------------------------------------------------------ other faults

    @Test
    fun `a missing task is misplaced`() {
        assertTrue(LayoutVerification.misplaced(leftTile, emptyList()))
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(pkg = "com.other.app"))))
    }

    @Test
    fun `a task in the wrong windowing mode is misplaced`() {
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(mode = WitsWindowMode.FULLSCREEN))))
    }

    @Test
    fun `an invisible task is misplaced`() {
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(visible = false))))
    }

    @Test
    fun `a tile on the wrong side is misplaced`() {
        // Right-hand tile geometry where the left was expected.
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(bounds = Rect(1560, 99, 2400, 900)))))
    }

    @Test
    fun `misplacedTiles reports each offender`() {
        val panel = ExpectedTile("io.github.miklergm.witscompanion", Rect(1560, 99, 2400, 900))
        val tasks = listOf(task())   // maps correct, panel absent
        val wrong = LayoutVerification.misplacedTiles(listOf(leftTile, panel), tasks)
        assertTrue(wrong.map { it.packageName } == listOf(panel.packageName))
    }

    // ------------------------------------------------- placement precondition

    @Test
    fun `an existing fullscreen task must be relaunched, not resized`() {
        // resizeTask only moves an already-freeform task, and setLaunchBounds is ignored for a
        // task that exists — so this one cannot be tiled without being torn down first.
        assertTrue(LayoutVerification.needsRelaunch(maps, listOf(task(mode = WitsWindowMode.FULLSCREEN))))
    }

    @Test
    fun `an existing freeform task is moved in place`() {
        // The route that keeps a live navigation session intact — no relaunch.
        assertFalse(LayoutVerification.needsRelaunch(maps, listOf(task())))
    }

    @Test
    fun `an app with no task needs no teardown`() {
        // Nothing to remove; the launch will carry the bounds itself.
        assertFalse(LayoutVerification.needsRelaunch(maps, emptyList()))
    }
}
