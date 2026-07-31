package io.github.miklergm.witscompanion

import android.graphics.Rect
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutIssue
import io.github.miklergm.witscompanion.layout.LayoutPreset
import io.github.miklergm.witscompanion.layout.LayoutValidator
import io.github.miklergm.witscompanion.layout.LayoutWindow
import io.github.miklergm.witscompanion.layout.PresetKind
import io.github.miklergm.witscompanion.layout.NormalizedBounds
import io.github.miklergm.witscompanion.wits.WitsPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Normalized -> pixel conversion, insets, and preset validation. */
@RunWith(RobolectricTestRunner::class)
class LayoutGeometryTest {

    private val display2400x900 = Rect(0, 0, 2400, 900)

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
}

/**
 * Broadcast scheduling.
 *
 * Regression guard for the retry storm observed on the vehicle on 2026-07-31: retry
 * passes used to fire every window back to back, which thrashes the task stack because
 * each CHANGE_WINDOW ends in startActivityFromRecents and pulls that task to the front.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutScheduleTest {

    private fun engine(): io.github.miklergm.witscompanion.layout.LayoutEngine {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        return io.github.miklergm.witscompanion.layout.LayoutEngine(
            appContext = ctx,
            windowController = io.github.miklergm.witscompanion.wits.WitsWindowController(ctx),
            reverseGuard = io.github.miklergm.witscompanion.safety.ReverseGuard(),
            rateLimiter = io.github.miklergm.witscompanion.safety.ActionRateLimiter(),
        )
    }

    // Each pass has two phases: CHANGE_WINDOW for every tile, then a launch for every
    // tile. scheduleFor() emits a pass as [geometry..., launches...] in that order.

    @Test
    fun `a pass sends all geometry before any launch`() {
        val n = 2
        val s = engine().scheduleFor(windowCount = n, retries = 0)
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
        val engine = engine()
        listOf(1, 2, 3, 4).forEach { n ->
            val s = engine.scheduleFor(windowCount = n, retries = 2)
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
        val engine = engine()
        val noRetry = engine.scheduleFor(windowCount = n, retries = 0)
        val oneRetry = engine.scheduleFor(windowCount = n, retries = 1)
        assertEquals(
            "a retry pass must add exactly one send per window",
            noRetry.size + n,
            oneRetry.size,
        )
    }

    @Test
    fun `two windows with no retries are staggered`() {
        val s = engine().scheduleFor(windowCount = 2, retries = 0)
        // geometry 0, 250 ; launch phase opens at 250+600=850, then 850+700=1550
        assertEquals(listOf(0L, 250L, 850L, 1550L), s)
    }

    @Test
    fun `no two broadcasts are ever scheduled at the same instant`() {
        val s = engine().scheduleFor(windowCount = 2, retries = 2)
        assertEquals("every send must have its own slot", s.size, s.distinct().size)
    }

    @Test
    fun `sends inside a pass are staggered, not fired back to back`() {
        val s = engine().scheduleFor(windowCount = 2, retries = 1).sorted()
        s.zipWithNext { a, b ->
            assertTrue("gap between $a and $b is too small", b - a >= 250L)
        }
    }

    @Test
    fun `a retry pass never overlaps the initial pass`() {
        val engine = engine()
        listOf(2, 3).forEach { n ->
            val s = engine.scheduleFor(windowCount = n, retries = 2)
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
        val engine = engine()
        // Initial pass is 2 sends per window (geometry + launch); each retry adds 1.
        assertEquals(4, engine.scheduleFor(2, retries = 0).size)
        assertEquals(6, engine.scheduleFor(2, retries = 1).size)
        assertEquals(8, engine.scheduleFor(2, retries = 2).size)
        assertEquals("cannot exceed MAX_RETRIES", 8, engine.scheduleFor(2, retries = 99).size)
    }

    @Test
    fun `single window still gets both phases`() {
        assertEquals(listOf(0L, 600L), engine().scheduleFor(windowCount = 1, retries = 0))
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
