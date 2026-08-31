package io.github.miklergm.witscompanion

import android.graphics.Rect
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutGeometry
import io.github.miklergm.witscompanion.layout.LayoutIssue
import io.github.miklergm.witscompanion.layout.LayoutPreset
import io.github.miklergm.witscompanion.layout.LayoutSchedule
import io.github.miklergm.witscompanion.layout.LayoutValidator
import io.github.miklergm.witscompanion.layout.LayoutWindow
import io.github.miklergm.witscompanion.layout.PresetKind
import io.github.miklergm.witscompanion.layout.NormalizedBounds
import io.github.miklergm.witscompanion.wits.WitsPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Normalized -> pixel conversion, insets, and preset validation. */
@RunWith(RobolectricTestRunner::class)
class LayoutGeometryTest {

    private val display2400x900 = Rect(0, 0, 2400, 900)

    // ------------------------------------------------- geometry vs. identity

    @Test
    fun `swapping sides does not change the preset id`() {
        // The persisted last-applied id must survive a geometry change. When withGeometry()
        // renamed the preset to "<id>_mirrored", toggling swap orphaned that stored id — the
        // same class of failure that once left a cold boot with no layout to restore.
        val base = DefaultPresets.all().first { it.splitFraction() != null }

        val plain = base.withGeometry(0.65f, swapped = false)
        val swapped = base.withGeometry(0.65f, swapped = true)

        assertEquals("identity is not a function of geometry", base.id, plain.id)
        assertEquals("identity is not a function of geometry", base.id, swapped.id)
        assertNotEquals(
            "the tiles must still actually swap",
            plain.windows.sortedBy { it.bounds.left }.first().packageName,
            swapped.windows.sortedBy { it.bounds.left }.first().packageName,
        )
    }

    @Test
    fun `withGeometry applies the requested split to both orders`() {
        val base = DefaultPresets.all().first { it.splitFraction() != null }

        val plain = base.withGeometry(0.4f, swapped = false)
        assertEquals(0.4f, plain.splitFraction()!!, 0.001f)

        // Mirrored, the primary app occupies the right-hand 40%: the left tile is the
        // complement, so the reported split is 0.6.
        val swapped = base.withGeometry(0.4f, swapped = true)
        assertEquals(0.6f, swapped.splitFraction()!!, 0.001f)
    }

    @Test
    fun `withGeometry is order-dependent, so it must be applied exactly once`() {
        // Documents why decoration happens where presets are produced and never again at
        // apply time: withSplit() re-derives tiles from their current left edges, so a second
        // pass over an already mirrored preset swaps the sides back.
        val base = DefaultPresets.all().first { it.splitFraction() != null }
        val once = base.withGeometry(0.65f, swapped = true)
        val twice = once.withGeometry(0.65f, swapped = true)

        assertNotEquals(
            "double decoration is not a no-op — hence the single-application rule",
            once.windows.sortedBy { it.bounds.left }.first().packageName,
            twice.windows.sortedBy { it.bounds.left }.first().packageName,
        )
    }

    @Test
    fun `full bounds map to the whole area`() {
        val px = NormalizedBounds.FULL.toPixels(display2400x900)
        assertEquals(Rect(0, 0, 2400, 900), px)
    }

    @Test
    fun `65 35 split maps to exact pixel columns`() {
        val left = NormalizedBounds(0f, 0f, 0.65f, 1f).toPixels(display2400x900)
        val right = NormalizedBounds(0.65f, 0f, 1f, 1f).toPixels(display2400x900)

        assertEquals(Rect(0, 0, 1560, 900), left)
        assertEquals(Rect(1560, 0, 2400, 900), right)
        // The two tiles must tile the display without a gap or overlap.
        assertEquals(left.right, right.left)
        assertEquals(display2400x900.width(), left.width() + right.width())
    }

    @Test
    fun `insets are honoured - bounds are relative to the usable area`() {
        // Usable area shifted down by a 48px status bar and inset 10px each side.
        val usable = Rect(10, 48, 2390, 900)
        val px = NormalizedBounds(0f, 0f, 0.5f, 1f).toPixels(usable)

        assertEquals(10, px.left)
        assertEquals(48, px.top)
        assertEquals(10 + (2380 / 2), px.right)
        assertEquals(900, px.bottom)
    }

    @Test
    fun `bounds validity rejects degenerate and out-of-range rectangles`() {
        assertTrue(NormalizedBounds(0f, 0f, 1f, 1f).isValid)
        assertFalse("left == right is degenerate", NormalizedBounds(0.5f, 0f, 0.5f, 1f).isValid)
        assertFalse("inverted", NormalizedBounds(0.8f, 0f, 0.2f, 1f).isValid)
        assertFalse("beyond 1.0", NormalizedBounds(0f, 0f, 1.2f, 1f).isValid)
        assertFalse("negative", NormalizedBounds(-0.1f, 0f, 1f, 1f).isValid)
    }

    @Test
    fun `overlap detection`() {
        val a = NormalizedBounds(0f, 0f, 0.65f, 1f)
        val b = NormalizedBounds(0.65f, 0f, 1f, 1f)
        val c = NormalizedBounds(0.5f, 0f, 1f, 1f)

        assertFalse("adjacent tiles do not overlap", a.overlaps(b))
        assertTrue("c intrudes into a", a.overlaps(c))
    }

    @Test
    fun `duplicate package is an ERROR - one window per package`() {
        val preset = LayoutPreset(
            id = "dup", title = "dup",
            windows = listOf(
                LayoutWindow("com.example.app", NormalizedBounds(0f, 0f, 0.5f, 1f)),
                LayoutWindow("com.example.app", NormalizedBounds(0.5f, 0f, 1f, 1f)),
            ),
        )
        val issues = LayoutValidator.validate(preset)
        assertTrue(LayoutValidator.hasErrors(issues))
        assertTrue(issues.any { it.message.contains("duplicate package") })
    }

    @Test
    fun `overlapping tiles produce a WARNING not an error`() {
        val preset = LayoutPreset(
            id = "ov", title = "ov",
            windows = listOf(
                LayoutWindow("com.a", NormalizedBounds(0f, 0f, 0.7f, 1f)),
                LayoutWindow("com.b", NormalizedBounds(0.5f, 0f, 1f, 1f)),
            ),
        )
        val issues = LayoutValidator.validate(preset)
        assertFalse(LayoutValidator.hasErrors(issues))
        assertTrue(issues.any {
            it.severity == LayoutIssue.Severity.WARNING && it.message.contains("overlap")
        })
    }

    @Test
    fun `invalid bounds produce an ERROR`() {
        val preset = LayoutPreset(
            id = "bad", title = "bad",
            windows = listOf(LayoutWindow("com.a", NormalizedBounds(0.9f, 0f, 0.1f, 1f))),
        )
        assertTrue(LayoutValidator.hasErrors(LayoutValidator.validate(preset)))
    }

    @Test
    fun `empty preset is an ERROR`() {
        assertTrue(
            LayoutValidator.hasErrors(
                LayoutValidator.validate(LayoutPreset("empty", "empty", emptyList()))
            )
        )
    }

    @Test
    fun `all shipped presets validate without errors`() {
        DefaultPresets.all().forEach { preset ->
            val issues = LayoutValidator.validate(preset)
            assertFalse(
                "preset ${preset.id} has errors: ${issues.map { it.message }}",
                LayoutValidator.hasErrors(issues),
            )
        }
    }

    @Test
    fun `focus order determines application order - highest is applied last`() {
        val preset = DefaultPresets.all().first { it.id == DefaultPresets.ID_MAPS_SPOTIFY }
        val ordered = preset.windows.sortedBy { it.focusOrder }
        assertEquals("com.google.android.apps.maps", ordered.first().packageName)
        assertEquals("com.spotify.music", ordered.last().packageName)
    }

    @Test
    fun `preset survives a json round trip`() {
        val original = DefaultPresets.all().first { it.id == DefaultPresets.ID_THREE_PANEL }
        val restored = LayoutPreset.fromJson(original.toJson())

        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.experimental, restored.experimental)
        assertEquals(original.windows.size, restored.windows.size)
        original.windows.zip(restored.windows).forEach { (a, b) ->
            assertEquals(a.packageName, b.packageName)
            assertEquals(a.windowMode, b.windowMode)
            assertEquals(a.focusOrder, b.focusOrder)
            assertEquals(a.bounds.left, b.bounds.left, 0.0001f)
            assertEquals(a.bounds.right, b.bounds.right, 0.0001f)
        }
    }
    // ------------------------------------------------------ a way back into the app

    /**
     * Whether applying a preset leaves anything of ours on screen.
     *
     * A tiled layout puts two foreign apps on the display and nothing else — no panel, no rail,
     * no gear. That is what a tiled layout *is*; it becomes a trap only when something
     * re-applies it automatically while the user is trying to reach the app, which is what
     * opening the companion from the launcher used to do. `[RUNTIME]` 2026-08-31.
     */
    @Test
    fun `an anchored preset always leaves a way back`() {
        // The panel is not one of its windows — the planner adds it — so this cannot be
        // answered by looking at the window list alone.
        val anchored = DefaultPresets.anchoredFor(WitsPackages.MAPS, "Maps")

        assertTrue(anchored.windows.none { it.packageName == WitsPackages.SELF })
        assertTrue(anchored.showsCompanion())
    }

    @Test
    fun `a tiled preset of two foreign apps does not`() {
        assertFalse(
            DefaultPresets.tiledFor(WitsPackages.MAPS, WitsPackages.CHROME, "Maps", "Chrome")
                .showsCompanion(),
        )
    }

    @Test
    fun `a tiled preset that includes the companion does`() {
        // The user can build one; it is a perfectly good layout and needs no special case.
        assertTrue(
            DefaultPresets.tiledFor(WitsPackages.SELF, WitsPackages.MAPS, "Cockpit", "Maps")
                .showsCompanion(),
        )
    }

    // ------------------------------------------------- the two tiles, together

    /**
     * The invariant nothing checked before the geometry came out of [LayoutEngine].
     *
     * The engine places the floating app in the app tile while the panel resizes *its own*
     * task to the complement, from two different call paths at two different moments. If they
     * disagree by a pixel it shows as a seam or an overlap on a display where both are visible
     * at once — and the only way to find that out was to look at the screen.
     */
    @Test
    fun `the app tile and the panel exactly cover the display, at every split`() {
        val area = Rect(0, 99, 2400, 900)
        for (step in 0..LayoutPreset.SPLIT_STEPS) {
            val split = LayoutPreset.progressToSplit(step)
            listOf(false, true).forEach { swapped ->
                val app = LayoutGeometry.appBounds(split, swapped, area)
                val panel = LayoutGeometry.panelBounds(
                    split, swapped, hidden = false, area = area, full = display2400x900,
                )
                val where = "split=$split swapped=$swapped"

                assertEquals("$where: same height", area.top, minOf(app.top, panel.top))
                assertEquals("$where: same bottom", area.bottom, maxOf(app.bottom, panel.bottom))
                assertEquals("$where: they cover the width", area.width(), app.width() + panel.width())
                assertTrue(
                    "$where: they must meet exactly, not overlap or leave a gap",
                    app.right == panel.left || panel.right == app.left,
                )
            }
        }
    }

    @Test
    fun `swapping puts the panel on the other side and nothing else changes`() {
        val area = Rect(0, 99, 2400, 900)
        val normal = LayoutGeometry.appBounds(0.65f, swapped = false, area = area)
        val mirrored = LayoutGeometry.appBounds(0.65f, swapped = true, area = area)

        assertEquals(normal.width(), mirrored.width())
        assertEquals(area.left, normal.left)
        assertEquals(area.right, mirrored.right)
    }

    @Test
    fun `a hidden app gives the panel the whole display, status bar included`() {
        // Not the usable area: the hidden state paints the freed strip itself, and a panel
        // stopping at the inset would leave the vendor bar showing through.
        assertEquals(
            display2400x900,
            LayoutGeometry.panelBounds(
                0.65f, swapped = false, hidden = true,
                area = Rect(0, 99, 2400, 900), full = display2400x900,
            ),
        )
    }

    @Test
    fun `a window that is not flush to an edge has no complement`() {
        // Nothing sensible to give the panel, so the caller keeps it fullscreen.
        assertNull(
            LayoutGeometry.panelComplement(
                NormalizedBounds(0.2f, 0f, 0.8f, 1f), Rect(0, 99, 2400, 900),
            ),
        )
    }

    @Test
    fun `the tile geometry clamps the split to the allowed range`() {
        val area = Rect(0, 0, 1000, 100)
        assertEquals(
            (LayoutPreset.MIN_SPLIT * 1000).toInt(),
            LayoutGeometry.appBounds(0.01f, swapped = false, area = area).width(),
        )
        assertEquals(
            (LayoutPreset.MAX_SPLIT * 1000).toInt(),
            LayoutGeometry.appBounds(0.99f, swapped = false, area = area).width(),
        )
    }

    // ------------------------------------------------------- the split scale

    /**
     * The slider round-trip, which used to lose a percent per visit.
     *
     * `(0.65f - 0.25f) * 100` is 39.999996 in float, so truncating put the default split on
     * step 39. Opening the layout settings therefore showed "64 / 36" for a stored 0.65, and
     * releasing the slider without moving it wrote 0.64 back — a percent gone each time, and
     * nothing on screen to suggest the widget rather than the setting was at fault.
     */
    @Test
    fun `the default split survives the slider round trip`() {
        val step = LayoutPreset.splitToProgress(LayoutPreset.DEFAULT_SPLIT)

        assertEquals(40, step)
        assertEquals(
            65,
            LayoutPreset.splitPercent(LayoutPreset.progressToSplit(step)),
        )
    }

    @Test
    fun `every whole percent in range round trips to itself`() {
        for (step in 0..LayoutPreset.SPLIT_STEPS) {
            val split = LayoutPreset.progressToSplit(step)
            assertEquals(
                "step $step -> $split -> back",
                step,
                LayoutPreset.splitToProgress(split),
            )
        }
    }

    @Test
    fun `the scale spans the allowed range and clamps outside it`() {
        assertEquals(55, LayoutPreset.SPLIT_STEPS)
        assertEquals(LayoutPreset.MIN_SPLIT, LayoutPreset.progressToSplit(0))
        assertEquals(LayoutPreset.MAX_SPLIT, LayoutPreset.progressToSplit(LayoutPreset.SPLIT_STEPS), 0.0001f)
        assertEquals(0, LayoutPreset.splitToProgress(0.10f))
        assertEquals(LayoutPreset.SPLIT_STEPS, LayoutPreset.splitToProgress(0.99f))
    }

    @Test
    fun `split percent rounds rather than truncating`() {
        assertEquals(65, LayoutPreset.splitPercent(0.65f))
        assertEquals(64, LayoutPreset.splitPercent(0.6449f))
        assertEquals(50, LayoutPreset.splitPercent(0.5f))
    }
}

/**
 * Broadcast scheduling.
 *
 * Regression guard for the retry storm observed on the vehicle on 2026-07-31: retry
 * passes used to fire every window back to back, which thrashes the task stack because
 * each CHANGE_WINDOW ends in startActivityFromRecents and pulls that task to the front.
 */
class LayoutScheduleTest {

    // Plain JUnit: the schedule is arithmetic, and since it moved out of LayoutEngine it no
    // longer needs a Context, a window controller and two guards constructed to ask about it.

    // Each pass has two phases: CHANGE_WINDOW for every tile, then a launch for every
    // tile. scheduleFor() emits a pass as [geometry..., launches...] in that order.

    @Test
    fun `a pass sends all geometry before any launch`() {
        val n = 2
        val s = LayoutSchedule.scheduleFor(windowCount = n, retries = 0)
        val geometry = s.take(n)
        val launches = s.drop(n)
        assertTrue(
            "the last CHANGE_WINDOW must land before the first launch",
            geometry.max() < launches.min(),
        )
    }

    /**
     * The regression guard for the visibility bug: interleaving the phases means a later
     * CHANGE_WINDOW fires after an earlier launch, and the vendor hook's warm path hides
     * the tile that launch had just made visible.
     */
    @Test
    fun `phases never interleave, for any window count`() {
        listOf(1, 2, 3, 4).forEach { n ->
            val s = LayoutSchedule.scheduleFor(windowCount = n, retries = 2)
            val geometryEnd = s.take(n).max()
            val firstLaunch = s.drop(n).min()
            assertTrue(
                "n=$n: geometry ends at $geometryEnd but a launch fires at $firstLaunch",
                geometryEnd < firstLaunch,
            )
        }
    }

    /**
     * A retry must not re-send CHANGE_WINDOW. That call is exclusive — it hides every
     * other freeform tile — so a retry containing geometry makes the layout visibly
     * flash on every pass.
     */
    @Test
    fun `retry passes contain launches only`() {
        val n = 2
        val noRetry = LayoutSchedule.scheduleFor(windowCount = n, retries = 0)
        val oneRetry = LayoutSchedule.scheduleFor(windowCount = n, retries = 1)
        assertEquals(
            "a retry pass must add exactly one send per window",
            noRetry.size + n,
            oneRetry.size,
        )
    }

    @Test
    fun `two windows with no retries are staggered`() {
        val s = LayoutSchedule.scheduleFor(windowCount = 2, retries = 0)
        // geometry 0, 250 ; launch phase opens at 250+600=850, then 850+700=1550
        assertEquals(listOf(0L, 250L, 850L, 1550L), s)
    }

    @Test
    fun `no two broadcasts are ever scheduled at the same instant`() {
        val s = LayoutSchedule.scheduleFor(windowCount = 2, retries = 2)
        assertEquals("every send must have its own slot", s.size, s.distinct().size)
    }

    @Test
    fun `sends inside a pass are staggered, not fired back to back`() {
        val s = LayoutSchedule.scheduleFor(windowCount = 2, retries = 1).sorted()
        s.zipWithNext { a, b ->
            assertTrue("gap between $a and $b is too small", b - a >= 250L)
        }
    }

    @Test
    fun `a retry pass never overlaps the initial pass`() {
        listOf(2, 3).forEach { n ->
            val s = LayoutSchedule.scheduleFor(windowCount = n, retries = 2)
            val initialEnd = s.take(2 * n).max()
            val firstRetry = s.drop(2 * n).min()
            assertTrue(
                "retry must start after the initial pass (n=$n)",
                firstRetry > initialEnd,
            )
        }
    }

    @Test
    fun `retries are bounded and default to one pass`() {
        // Initial pass is 2 sends per window (geometry + launch); each retry adds 1.
        assertEquals(4, LayoutSchedule.scheduleFor(2, retries = 0).size)
        assertEquals(6, LayoutSchedule.scheduleFor(2, retries = 1).size)
        assertEquals(8, LayoutSchedule.scheduleFor(2, retries = 2).size)
        assertEquals("cannot exceed MAX_RETRIES", 8, LayoutSchedule.scheduleFor(2, retries = 99).size)
    }

    /**
     * The case that was actually inverted on the vehicle's own layout.
     *
     * Initial placement is pushed back by stale-window parking and, for an anchored preset, the
     * anchor settle; retries and verification were measured from `apply()` regardless. One
     * anchored window with one stale window put the first retry at 1200 ms and the launch it
     * exists to repair at 1300 ms — a retry firing before there was anything to retry.
     */
    @Test
    fun `a retry never precedes the launch it repairs, even after preparation`() {
        val prep = LayoutSchedule.preparation(parkedWindows = 1, anchored = true)
        assertEquals(700L, prep)

        val s = LayoutSchedule.scheduleFor(windowCount = 1, retries = 2, preparationMs = prep)
        val initial = s.take(2)
        val retries = s.drop(2)

        assertEquals(listOf(700L, 1300L), initial)
        assertTrue("first retry at ${retries.min()}, launch at ${initial.max()}",
            retries.min() > initial.max())
    }

    @Test
    fun `preparation shifts the whole timeline by the same amount`() {
        val plain = LayoutSchedule.scheduleFor(windowCount = 2, retries = 2)
        val shifted = LayoutSchedule.scheduleFor(windowCount = 2, retries = 2, preparationMs = 700L)

        assertEquals(plain.map { it + 700L }, shifted)
    }

    @Test
    fun `preparation counts a park per window and the settle only when anchored`() {
        assertEquals(0L, LayoutSchedule.preparation(parkedWindows = 0, anchored = false))
        assertEquals(450L, LayoutSchedule.preparation(parkedWindows = 0, anchored = true))
        assertEquals(500L, LayoutSchedule.preparation(parkedWindows = 2, anchored = false))
    }

    @Test
    fun `single window still gets both phases`() {
        assertEquals(listOf(0L, 600L), LayoutSchedule.scheduleFor(windowCount = 1, retries = 0))
    }
}

/**
 * Preset kinds, side swapping and split adjustment.
 *
 * These cover the layout customisation the user asked for ("Spotify on the left, or make
 * it configurable") and the ANCHORED arrangement in which the companion is the fullscreen
 * anchor and exactly one foreign window floats above it.
 */
@RunWith(RobolectricTestRunner::class)
class PresetKindAndCustomisationTest {

    private fun twoTile() = LayoutPreset(
        id = "p", title = "p",
        windows = listOf(
            LayoutWindow("com.maps", NormalizedBounds(0f, 0f, 0.65f, 1f), focusOrder = 0),
            LayoutWindow("com.music", NormalizedBounds(0.65f, 0f, 1f, 1f), focusOrder = 1),
        ),
    )

    @Test
    fun `mirroring swaps the two sides and keeps them adjacent`() {
        val m = twoTile().mirrored()
        val maps = m.windows.first { it.packageName == "com.maps" }
        val music = m.windows.first { it.packageName == "com.music" }

        assertEquals(0.35f, maps.bounds.left, 0.0001f)
        assertEquals(1f, maps.bounds.right, 0.0001f)
        assertEquals(0f, music.bounds.left, 0.0001f)
        assertEquals(0.35f, music.bounds.right, 0.0001f)
        assertEquals("still adjacent", music.bounds.right, maps.bounds.left, 0.0001f)
    }

    @Test
    fun `mirroring twice returns the original geometry`() {
        val original = twoTile()
        val twice = original.mirrored().mirrored()
        original.windows.zip(twice.windows.sortedBy { it.bounds.left }).forEach { (a, b) ->
            assertEquals(a.bounds.left, b.bounds.left, 0.0001f)
            assertEquals(a.bounds.right, b.bounds.right, 0.0001f)
        }
    }

    @Test
    fun `mirrored preset still validates`() {
        assertFalse(LayoutValidator.hasErrors(LayoutValidator.validate(twoTile().mirrored())))
    }

    @Test
    fun `split fraction is read back correctly`() {
        assertEquals(0.65f, twoTile().splitFraction()!!, 0.0001f)
    }

    @Test
    fun `split can be adjusted and stays gapless`() {
        val p = twoTile().withSplit(0.5f)
        val sorted = p.windows.sortedBy { it.bounds.left }
        assertEquals(0.5f, sorted[0].bounds.right, 0.0001f)
        assertEquals(0.5f, sorted[1].bounds.left, 0.0001f)
        assertEquals(0f, sorted[0].bounds.left, 0.0001f)
        assertEquals(1f, sorted[1].bounds.right, 0.0001f)
        assertFalse(LayoutValidator.hasErrors(LayoutValidator.validate(p)))
    }

    @Test
    fun `split is clamped to a usable range`() {
        assertEquals(LayoutPreset.MIN_SPLIT, twoTile().withSplit(0.01f).splitFraction()!!, 0.0001f)
        assertEquals(LayoutPreset.MAX_SPLIT, twoTile().withSplit(0.99f).splitFraction()!!, 0.0001f)
    }

    @Test
    fun `split adjustment is a no-op for presets that are not a simple two-way split`() {
        val single = LayoutPreset(
            "s", "s", listOf(LayoutWindow("com.a", NormalizedBounds.FULL))
        )
        assertEquals(single.windows, single.withSplit(0.5f).windows)
        assertNull(single.splitFraction())
    }

    @Test
    fun `anchored preset must carry exactly one foreign window`() {
        val bad = LayoutPreset(
            "a", "a", kind = PresetKind.TILED,
            windows = listOf(
                LayoutWindow("com.maps", NormalizedBounds(0f, 0f, 0.65f, 1f)),
                LayoutWindow("com.music", NormalizedBounds(0.65f, 0f, 1f, 1f)),
            ),
        ).copy(kind = PresetKind.ANCHORED)

        val issues = LayoutValidator.validate(bad)
        assertTrue(LayoutValidator.hasErrors(issues))
        assertTrue(issues.any { it.message.contains("exactly one foreign window") })
    }

    @Test
    fun `the companion must not be a tile in an anchored preset`() {
        val bad = LayoutPreset(
            "a", "a", kind = PresetKind.ANCHORED,
            windows = listOf(
                LayoutWindow(
                    io.github.miklergm.witscompanion.wits.WitsPackages.SELF,
                    NormalizedBounds(0.65f, 0f, 1f, 1f),
                )
            ),
        )
        val issues = LayoutValidator.validate(bad)
        assertTrue(LayoutValidator.hasErrors(issues))
        assertTrue(issues.any { it.message.contains("anchor") })
    }

    @Test
    fun `the shipped anchored preset is valid and has one window`() {
        val p = DefaultPresets.all().first { it.id == DefaultPresets.ID_MAPS_ANCHORED }
        assertEquals(PresetKind.ANCHORED, p.kind)
        assertEquals(1, p.windows.size)
        assertFalse(LayoutValidator.hasErrors(LayoutValidator.validate(p)))
    }

    @Test
    fun `kind survives a json round trip`() {
        val p = DefaultPresets.all().first { it.id == DefaultPresets.ID_MAPS_ANCHORED }
        assertEquals(PresetKind.ANCHORED, LayoutPreset.fromJson(p.toJson()).kind)
        val tiled = twoTile()
        assertEquals(PresetKind.TILED, LayoutPreset.fromJson(tiled.toJson()).kind)
    }

    // ------------------------------------------------------- shared geometry

    @Test
    fun `one split drives a tiled pair`() {
        val p = twoTile().withGeometry(split = 0.5f, swapped = false)
        val sorted = p.windows.sortedBy { it.bounds.left }
        assertEquals(0f, sorted[0].bounds.left, 0.001f)
        assertEquals(0.5f, sorted[0].bounds.right, 0.001f)
        assertEquals(0.5f, sorted[1].bounds.left, 0.001f)
        assertEquals(1f, sorted[1].bounds.right, 0.001f)
    }

    @Test
    fun `swapping puts the primary app on the right`() {
        val plain = twoTile().withGeometry(0.65f, swapped = false)
        val swapped = twoTile().withGeometry(0.65f, swapped = true)
        val primary = plain.windows.maxByOrNull { it.bounds.width }!!.packageName
        val swappedPrimary = swapped.windows.maxByOrNull { it.bounds.width }!!
        assertEquals("the same app stays primary", primary, swappedPrimary.packageName)
        assertEquals("but now on the right", 1f, swappedPrimary.bounds.right, 0.001f)
    }

    /** An anchored preset has only the floating window, so the split is *its* width. */
    @Test
    fun `the split sets the floating window's width when anchored`() {
        val p = DefaultPresets.all().first { it.id == DefaultPresets.ID_MAPS_ANCHORED }
        val narrow = p.withGeometry(0.5f, swapped = false)
        assertEquals(1, narrow.windows.size)
        assertEquals(0f, narrow.windows[0].bounds.left, 0.001f)
        assertEquals(0.5f, narrow.windows[0].bounds.right, 0.001f)

        val right = p.withGeometry(0.5f, swapped = true)
        assertEquals(0.5f, right.windows[0].bounds.left, 0.001f)
        assertEquals(1f, right.windows[0].bounds.right, 0.001f)
    }

    @Test
    fun `a fullscreen preset ignores the geometry`() {
        val full = DefaultPresets.all().first { it.id == DefaultPresets.ID_MAPS_FULL }
        assertEquals(full, full.withGeometry(0.4f, swapped = true))
    }

    @Test
    fun `the split is clamped to the allowed range`() {
        val tiny = twoTile().withGeometry(0.01f, swapped = false)
        assertEquals(
            LayoutPreset.MIN_SPLIT,
            tiny.windows.minByOrNull { it.bounds.left }!!.bounds.right,
            0.001f,
        )
    }

    // --------------------------------------------------- user-built layouts

    @Test
    fun `a built pair honours order, focus and the shared geometry`() {
        val p = DefaultPresets.tiledFor("com.a", "com.b", "A", "B")
        assertEquals(PresetKind.TILED, p.kind)
        assertEquals("A + B", p.title)
        val ordered = p.windows.sortedBy { it.focusOrder }
        assertEquals("com.a", ordered.first().packageName)
        assertEquals("the last-applied window takes focus", "com.b", ordered.last().packageName)

        // The proportion is applied on top, exactly like a built-in preset.
        val split = p.withGeometry(0.5f, swapped = false)
        assertEquals(0.5f, split.windows.minByOrNull { it.bounds.left }!!.bounds.right, 0.001f)
    }

    @Test
    fun `a built pair passes validation`() {
        val p = DefaultPresets.tiledFor("com.a", "com.b", "A", "B")
        assertFalse(LayoutValidator.hasErrors(LayoutValidator.validate(p)))
    }

    @Test
    fun `built pairs get distinct ids so saving does not collide`() {
        val ab = DefaultPresets.tiledFor("com.a", "com.b", "A", "B")
        val ba = DefaultPresets.tiledFor("com.b", "com.a", "B", "A")
        assertNotEquals(ab.id, ba.id)
    }

    // ------------------------------------------------- anchor panel reservation

    @Test
    fun `the anchor panel reserves exactly the floating window's strip`() {
        val p = DefaultPresets.all().first { it.id == DefaultPresets.ID_MAPS_ANCHORED }
        assertEquals(0.65f, p.anchorReservedLeftFraction(), 0.001f)
    }

    @Test
    fun `a tiled preset reserves nothing`() {
        assertEquals(0f, twoTile().anchorReservedLeftFraction(), 0.001f)
    }

    /**
     * A window that does not start at the left edge would leave the gap in the wrong
     * place, so the panel stays full width rather than hiding content under the map.
     */
    @Test
    fun `a floating window away from the left edge reserves nothing`() {
        val p = LayoutPreset(
            id = "right_anchored",
            title = "right",
            kind = PresetKind.ANCHORED,
            windows = listOf(
                LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0.35f, 0f, 1f, 1f)),
            ),
        )
        assertEquals(0f, p.anchorReservedLeftFraction(), 0.001f)
    }

    @Test
    fun `the reservation never starves the panel`() {
        val p = LayoutPreset(
            id = "greedy",
            title = "greedy",
            kind = PresetKind.ANCHORED,
            windows = listOf(
                LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0f, 0f, 0.98f, 1f)),
            ),
        )
        assertEquals(LayoutPreset.MAX_ANCHOR_RESERVED, p.anchorReservedLeftFraction(), 0.001f)
    }

}
