package io.github.miklergm.witscompanion

import android.graphics.Rect
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutPlan
import io.github.miklergm.witscompanion.layout.LayoutPlanner
import io.github.miklergm.witscompanion.layout.LayoutSchedule
import io.github.miklergm.witscompanion.layout.LayoutWindow
import io.github.miklergm.witscompanion.layout.NormalizedBounds
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What an apply has decided, before it does any of it.
 *
 * These decisions used to be interleaved with `handler.postDelayed` calls inside a 200-line
 * `LayoutEngine.apply()`: which windows are installed, where stale ones get parked, whether the
 * panel gets a complement tile, which window is fronted, what the result is compared against.
 * None of it could be inspected without a device, so the only way to find out what an apply
 * would do was to watch what it did — on a vehicle, one attempt at a time.
 *
 * The planner itself reads nothing but its arguments. Robolectric only for `android.graphics
 * .Rect`, which is a stub that silently compares unequal on the plain unit-test classpath.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutPlannerTest {

    /** This unit: 2400×900 with a 99 px status bar. */
    private val area = Rect(0, 99, 2400, 900)
    private val full = Rect(0, 0, 2400, 900)

    private fun anchored(split: Float = 0.65f, swapped: Boolean = false) =
        DefaultPresets.anchoredFor(WitsPackages.MAPS, "Maps").withGeometry(split, swapped)

    private fun tiled() =
        DefaultPresets.tiledFor(WitsPackages.MAPS, WitsPackages.CHROME, "Maps", "Chrome")

    private fun plan(
        preset: io.github.miklergm.witscompanion.layout.LayoutPreset = anchored(),
        preserveLive: Set<String> = emptySet(),
        isLaunchable: (String) -> Boolean = { true },
    ) = LayoutPlanner.plan(preset, area, full, preserveLive, isLaunchable)

    // ------------------------------------------------------------------ the panel

    @Test
    fun `an anchored preset plans the panel as the strip the map does not cover`() {
        val p = plan()

        assertEquals(Rect(1560, 99, 2400, 900), p.panelBounds)
        assertEquals(
            "the map takes the rest of it",
            Rect(0, 99, 1560, 900),
            p.expected.first { it.packageName == WitsPackages.MAPS }.bounds,
        )
        assertTrue("the panel is verified too", p.expected.any { it.packageName == WitsPackages.SELF })
    }

    @Test
    fun `swapping mirrors both tiles and they still meet exactly`() {
        val p = plan(anchored(swapped = true))

        assertEquals(Rect(0, 99, 840, 900), p.panelBounds)
        assertEquals(
            Rect(840, 99, 2400, 900),
            p.expected.first { it.packageName == WitsPackages.MAPS }.bounds,
        )
    }

    @Test
    fun `a tiled preset has no panel of its own`() {
        assertNull(plan(tiled()).panelBounds)
        assertFalse(plan(tiled()).anchored)
    }

    // ------------------------------------------------------------------ parking

    @Test
    fun `an anchored layout parks stale windows behind the floating tile`() {
        // Not fullscreen: the panel is a tile now, so a fullscreen park would fill the screen
        // and cover it.
        val p = plan()

        assertEquals(Rect(0, 99, 1560, 900), p.park.bounds)
        assertEquals(WitsWindowMode.FREEFORM, p.park.mode)
    }

    @Test
    fun `a tiled layout parks them fullscreen, out of the way of both tiles`() {
        val p = plan(tiled())

        assertEquals(full, p.park.bounds)
        assertEquals(WitsWindowMode.FULLSCREEN, p.park.mode)
    }

    @Test
    fun `everything in the preset is kept, installed or not`() {
        // `keep` decides what is NOT parked. A window whose app is missing must still be kept:
        // parking is keyed on package, and the set is also what the engine records as applied.
        val p = plan(tiled(), isLaunchable = { it == WitsPackages.MAPS })

        assertEquals(setOf(WitsPackages.MAPS, WitsPackages.CHROME), p.keep)
    }

    // -------------------------------------------------------------------- fronting

    @Test
    fun `the anchored floating app is fronted, and only it`() {
        // A tile hidden behind the previous app — or behind the launcher after the vendor Home
        // button — would otherwise be resized in place and stay hidden.
        val p = plan()
        val geometry = p.steps.filter { it.phase == LayoutPlan.Phase.GEOMETRY }

        assertEquals(listOf(true), geometry.map { it.bringToFront })
    }

    @Test
    fun `a tiled preset fronts nothing`() {
        // Both tiles are placed side by side; fronting one would reorder the other away.
        val p = plan(tiled())

        assertTrue(p.steps.none { it.bringToFront })
    }

    // ---------------------------------------------------------------------- timing

    @Test
    fun `geometry is staggered and every launch follows the last of it`() {
        val p = plan(tiled())
        val geometry = p.steps.filter { it.phase == LayoutPlan.Phase.GEOMETRY }.map { it.offsetMs }
        val launches = p.steps.filter { it.phase == LayoutPlan.Phase.LAUNCH }.map { it.offsetMs }

        assertEquals(listOf(0L, LayoutSchedule.GEOMETRY_DELAY_MS), geometry)
        assertEquals(
            listOf(
                LayoutSchedule.launchPhaseStart(2),
                LayoutSchedule.launchPhaseStart(2) + LayoutSchedule.LAUNCH_DELAY_MS,
            ),
            launches,
        )
        assertTrue(launches.min() > geometry.max())
    }

    @Test
    fun `a window that cannot be placed leaves its slot in the schedule`() {
        // Offsets are keyed on the position in the preset, not on the position among the steps
        // that survived. Re-indexing would pull the second tile's geometry forward on top of
        // the first one's, and both phases are staggered precisely because sends back to back
        // do not reinforce a layout.
        val p = plan(tiled(), isLaunchable = { it == WitsPackages.CHROME })
        val chrome = p.steps.first { it.phase == LayoutPlan.Phase.GEOMETRY }

        assertEquals(WitsPackages.CHROME, chrome.packageName)
        assertEquals(1, chrome.index)
        assertEquals(LayoutSchedule.GEOMETRY_DELAY_MS, chrome.offsetMs)
    }

    @Test
    fun `pass timing counts the preset's windows, not the placeable ones`() {
        // windowCount drives the retry and verification delays, which have to clear the pass
        // the schedule was laid out for.
        assertEquals(2, plan(tiled(), isLaunchable = { false }).windowCount)
    }

    // ------------------------------------------------------------------ what is left out

    @Test
    fun `an uninstalled app is skipped, reported, and not expected`() {
        val p = plan(tiled(), isLaunchable = { it == WitsPackages.MAPS })

        assertEquals(listOf(WitsPackages.CHROME), p.skipped)
        assertEquals(listOf(WitsPackages.MAPS), p.expected.map { it.packageName })
        assertTrue(p.steps.none { it.packageName == WitsPackages.CHROME })
    }

    @Test
    fun `a tiled preset with nothing installed places nothing`() {
        assertTrue(plan(tiled(), isLaunchable = { false }).placesNothing)
    }

    @Test
    fun `an anchored preset with nothing installed still places the panel`() {
        // Deliberate: the panel is the Cockpit, and it comes up whether or not the app does.
        val p = plan(isLaunchable = { false })

        assertFalse(p.placesNothing)
        assertEquals(listOf(WitsPackages.SELF), p.expected.map { it.packageName })
    }

    // --------------------------------------------------------------------- deep links

    @Test
    fun `a deep link rides on the geometry step, so the task exists before it is moved`() {
        val preset = tiled().let { t ->
            t.copy(windows = listOf(t.windows[0].copy(launchIntentUri = "geo:0,0?q=home"), t.windows[1]))
        }
        val p = plan(preset)

        assertEquals(
            "geo:0,0?q=home",
            p.steps.first { it.phase == LayoutPlan.Phase.GEOMETRY }.launchIntentUri,
        )
        assertTrue(
            "and never on a launch, which would fire it a second time",
            p.steps.filter { it.phase == LayoutPlan.Phase.LAUNCH }.all { it.launchIntentUri == null },
        )
    }

    // -------------------------------------------------------------------------- purity

    @Test
    fun `planning the same thing twice gives the same plan`() {
        // It reads nothing but its arguments — no clock, no window controller, no stored state.
        assertEquals(plan(), plan())
    }

    @Test
    fun `focus order decides the sequence, not the order the windows were written in`() {
        val preset = tiled().let { t ->
            t.copy(
                windows = listOf(
                    LayoutWindow(WitsPackages.CHROME, NormalizedBounds(0.65f, 0f, 1f, 1f), focusOrder = 1),
                    LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0f, 0f, 0.65f, 1f), focusOrder = 0),
                )
            )
        }
        val p = plan(preset)

        assertEquals(
            "the highest focusOrder is placed last and ends up focused",
            listOf(WitsPackages.MAPS, WitsPackages.CHROME),
            p.steps.filter { it.phase == LayoutPlan.Phase.GEOMETRY }.map { it.packageName },
        )
    }
}
