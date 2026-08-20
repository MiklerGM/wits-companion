package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.carstate.Availability
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.SignalSource
import io.github.miklergm.witscompanion.carstate.SignalValue
import io.github.miklergm.witscompanion.layout.CockpitLeft
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutPreset
import io.github.miklergm.witscompanion.layout.LayoutWindow
import io.github.miklergm.witscompanion.layout.NormalizedBounds
import io.github.miklergm.witscompanion.layout.PresetKind
import io.github.miklergm.witscompanion.media.MediaSnapshot
import io.github.miklergm.witscompanion.ui.CockpitState
import io.github.miklergm.witscompanion.wits.HotspotController
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cockpit panel's display decisions.
 *
 * All of this was previously inline in a 1000-line Activity, reachable only by running the
 * app on the head unit — so none of it had a test, including the rail-highlight resolution
 * that has misbehaved on-car more than once.
 */
class CockpitStateTest {

    private fun anchored(pkg: String, id: String = "anchored_$pkg") = LayoutPreset(
        id = id,
        title = "$pkg over panel",
        kind = PresetKind.ANCHORED,
        windows = listOf(LayoutWindow(pkg, NormalizedBounds(0f, 0f, 0.65f, 1f))),
    )

    private fun tiled() = LayoutPreset(
        id = "tiled",
        title = "two apps",
        kind = PresetKind.TILED,
        windows = listOf(
            LayoutWindow("a.b.c", NormalizedBounds(0f, 0f, 0.65f, 1f)),
            LayoutWindow("d.e.f", NormalizedBounds(0.65f, 0f, 1f, 1f)),
        ),
    )

    // ------------------------------------------------------- floating package

    @Test
    fun `a remembered app wins over any preset`() {
        // The remembered package is what resolves the switcher's on-the-fly anchored_<pkg>
        // presets, which are never stored and so cannot be found by searching saved ones.
        assertEquals(
            "com.spotify.music",
            CockpitState.floatingPackage(
                cockpitLeft = CockpitLeft.App("com.spotify.music"),
                lastAppliedPreset = anchored(WitsPackages.MAPS),
                defaultAnchored = anchored(WitsPackages.MAPS),
            ),
        )
    }

    @Test
    fun `hidden and config light no app tile`() {
        listOf(CockpitLeft.Hidden, CockpitLeft.Config).forEach { left ->
            assertNull(
                "$left must not light an app tile",
                CockpitState.floatingPackage(left, anchored(WitsPackages.MAPS), anchored(WitsPackages.MAPS)),
            )
        }
    }

    @Test
    fun `default falls back to the last anchored preset, then to the map`() {
        assertEquals(
            "com.waze",
            CockpitState.floatingPackage(CockpitLeft.Default, anchored("com.waze"), anchored(WitsPackages.MAPS)),
        )
        assertEquals(
            "with no last preset, the default map preset answers",
            WitsPackages.MAPS,
            CockpitState.floatingPackage(CockpitLeft.Default, null, anchored(WitsPackages.MAPS)),
        )
    }

    @Test
    fun `a tiled last preset is not treated as the floating app`() {
        // Only an ANCHORED preset floats something over the panel. A tiled layout has no
        // floating app at all, and reading its first window as one would light the wrong tile.
        assertEquals(
            WitsPackages.MAPS,
            CockpitState.floatingPackage(CockpitLeft.Default, tiled(), anchored(WitsPackages.MAPS)),
        )
        assertNull(
            CockpitState.floatingPackage(CockpitLeft.Default, tiled(), null),
        )
    }

    @Test
    fun `the panel itself is never the floating app`() {
        val selfOnly = LayoutPreset(
            id = "odd", title = "panel only", kind = PresetKind.ANCHORED,
            windows = listOf(LayoutWindow(WitsPackages.SELF, NormalizedBounds(0f, 0f, 1f, 1f))),
        )
        assertNull(CockpitState.floatingPackage(CockpitLeft.Default, selfOnly, null))
    }

    // ------------------------------------------------------------------- rail

    @Test
    fun `the rail prefers the vendor slots and drops what is not installed`() {
        val installed = setOf("com.vendor.navi", WitsPackages.MAPS, WitsPackages.CHROME)
        val rail = CockpitState.railPackages(
            vendorNavigation = "com.vendor.navi",
            vendorMusic = "com.vendor.music",     // not installed
            vendorVideo = null,
            isLaunchable = installed::contains,
        )
        assertEquals(listOf("com.vendor.navi", WitsPackages.MAPS, WitsPackages.CHROME), rail)
    }

    @Test
    fun `the rail never exceeds its limit and never repeats`() {
        val rail = CockpitState.railPackages(
            vendorNavigation = WitsPackages.MAPS,   // same as the built-in suggestion
            vendorMusic = WitsPackages.SPOTIFY,
            vendorVideo = "com.vendor.video",
            isLaunchable = { true },
        )
        assertEquals("capped so the rail stays reachable", CockpitState.RAIL_LIMIT, rail.size)
        assertEquals("no duplicates", rail.size, rail.distinct().size)
    }

    // ------------------------------------------------------------ reservation

    @Test
    fun `a narrow tile reserves nothing`() {
        assertNull(CockpitState.reservation(fillsDisplay = false, split = 0.65f, swapped = false))
    }

    @Test
    fun `a full-width panel reserves the app strip on the correct side`() {
        val plain = CockpitState.reservation(fillsDisplay = true, split = 0.65f, swapped = false)!!
        assertFalse(plain.onRight)
        assertEquals(0.65f, plain.fraction, 0.001f)

        val swapped = CockpitState.reservation(fillsDisplay = true, split = 0.65f, swapped = true)!!
        assertTrue("swapped moves the reserved strip to the right", swapped.onRight)
    }

    // ------------------------------------------------------------------ media

    private fun snapshot(
        playing: Boolean = false,
        canPause: Boolean = false,
        duration: Long = 0L,
        position: Long = 0L,
        updatedAt: Long = 0L,
    ) = MediaSnapshot(
        available = true, permissionGranted = true, packageName = "p",
        title = "Song", artist = "Artist", album = "Album",
        isPlaying = playing, durationMs = duration,
        positionMs = position, positionUpdatedElapsedMs = updatedAt,
        canPlay = false, canPause = canPause, canSkipNext = true, canSkipPrevious = true,
    )

    @Test
    fun `play stays tappable when there is no live session at all`() {
        // The case the button exists for: with no session the player advertises nothing, and
        // play dispatches a media key that wakes its playback service. Disabled here, the
        // fallback is unreachable and the button is simply dead (`[RUNTIME]` 2026-08-17).
        val none = MediaSnapshot(available = false, permissionGranted = true)
        assertTrue(CockpitState.media(none).canPlayPause)
    }

    @Test
    fun `a live session that advertises nothing does not get a tappable play`() {
        // Deliberately different from the no-session case above: here a player is present and
        // saying it cannot act, which is information rather than an absence of it.
        val inert = CockpitState.media(snapshot(playing = false, canPause = false))
        assertFalse(inert.canPlayPause)
    }

    @Test
    fun `pause is offered only when the live session supports it`() {
        assertFalse(CockpitState.media(snapshot(playing = true, canPause = false)).canPlayPause)
        assertTrue(CockpitState.media(snapshot(playing = true, canPause = true)).canPlayPause)
    }

    @Test
    fun `no snapshot yields an empty panel`() {
        assertFalse(CockpitState.media(null).available)
        assertNull(CockpitState.media(null).raw)
    }

    @Test
    fun `an unavailable snapshot still reaches the view`() {
        // The view renders the "nothing playing" state from it, so it must not be dropped.
        val gone = MediaSnapshot(available = false, permissionGranted = true)
        val panel = CockpitState.media(gone)
        assertFalse(panel.available)
        assertNotNull("the raw snapshot is what the view renders from", panel.raw)
    }

    @Test
    fun `progress extrapolates from the player's own update time`() {
        // The bug this guards: position is as of positionUpdatedElapsedMs, not as of now.
        val m = CockpitState.media(
            snapshot(playing = true, duration = 200_000L, position = 30_000L, updatedAt = 1_000L)
        )
        assertEquals(30_000L, m.positionAt(now = 1_000L))
        assertEquals("5 s later, 5 s further in", 35_000L, m.positionAt(now = 6_000L))
    }

    @Test
    fun `a paused track does not creep forward`() {
        val m = CockpitState.media(
            snapshot(playing = false, duration = 200_000L, position = 30_000L, updatedAt = 1_000L)
        )
        assertEquals(30_000L, m.positionAt(now = 60_000L))
    }

    @Test
    fun `progress never runs past the end`() {
        val m = CockpitState.media(
            snapshot(playing = true, duration = 10_000L, position = 9_000L, updatedAt = 0L)
        )
        assertEquals(10_000L, m.positionAt(now = 999_000L))
    }

    @Test
    fun `no duration means no progress bar rather than a zero-length one`() {
        val m = CockpitState.media(snapshot(playing = true, duration = 0L))
        assertFalse(m.hasProgress)
        assertEquals(0L, m.positionAt(now = 5_000L))
    }

    // ---------------------------------------------------------------- hotspot

    @Test
    fun `hotspot reports busy only while it is changing`() {
        assertTrue(CockpitState.hotspot(true, HotspotController.State.TURNING_ON).busy)
        assertTrue(CockpitState.hotspot(true, HotspotController.State.TURNING_OFF).busy)
        assertFalse(CockpitState.hotspot(true, HotspotController.State.ON).busy)
        assertTrue(CockpitState.hotspot(true, HotspotController.State.ON).on)
        assertFalse(CockpitState.hotspot(true, HotspotController.State.FAILED).on)
    }

    // ------------------------------------------------------------- automation

    @Test
    fun `automation is offered only on a known-clear reverse`() {
        fun state(v: Boolean?) = CarState(
            reverse = v?.let { SignalValue(it, Availability.VALID, SignalSource.PROPERTY, 1_000L, "$it") }
                ?: SignalValue.unknown()
        )
        assertTrue(CockpitState.automationPermitted(state(false)))
        assertFalse("never over the camera", CockpitState.automationPermitted(state(true)))
        assertFalse("nor when merely unknown", CockpitState.automationPermitted(state(null)))
        assertFalse(
            "source BACKCAR is reverse evidence too",
            CockpitState.automationPermitted(
                CarState(source = SignalValue(WitsSource.BACKCAR, Availability.VALID, SignalSource.PROPERTY, 1_000L, "0"))
            ),
        )
    }
}
