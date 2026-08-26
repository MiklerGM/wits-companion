package io.github.miklergm.witscompanion.layout

import android.graphics.Rect
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsWindowMode

/**
 * Everything an apply has decided to do, before any of it happens.
 *
 * `LayoutEngine.apply()` used to decide and act in one pass: which windows are installed, where
 * stale ones get parked, whether the panel gets a complement tile, which window is fronted,
 * what the result will be compared against — all of it interleaved with `handler.postDelayed`
 * calls. None of those decisions could be inspected without a device, so the only way to find
 * out what an apply would do was to watch what it did.
 *
 * Separating them buys a real check as well as a testable one. The old code refused twice: a
 * preflight for "no app in this layout is installed", and a second, identical refusal at the
 * very end for the case the preflight could not see — an anchored preset whose panel has no
 * bounds either. That second one fired *after* the stale-window cleanup had been scheduled and
 * the generation bumped, which is the same "a refusal must cost nothing" defect that was fixed
 * once already. With the plan computed up front there is one refusal, and it is a preflight:
 * [expected] being empty means nothing would be placed, whatever the reason.
 */
data class LayoutPlan(
    /** Packages this layout keeps, so anything else on screen can be parked. */
    val keep: Set<String>,
    /** Where stale windows are moved to, and in which mode. */
    val park: Park,
    /** The panel tile for an anchored preset, or null when the panel stays fullscreen. */
    val panelBounds: Rect?,
    /** The window changes, in order, timed from the start of the placement phase. */
    val steps: List<Step>,
    /** What the screen should look like afterwards — the yardstick for verification. */
    val expected: List<ExpectedTile>,
    /** Packages in the preset that cannot be placed, because nothing is installed for them. */
    val skipped: List<String>,
    /** Windows in the preset, launchable or not — what the pass timing is measured against. */
    val windowCount: Int,
    /** True when this preset brings the companion up as a panel tile. */
    val anchored: Boolean,
) {

    /** Nothing would reach the screen; the caller should refuse rather than mutate anything. */
    val placesNothing: Boolean get() = expected.isEmpty()

    data class Park(val bounds: Rect, val mode: Int)

    enum class Phase {
        /** Sets bounds and windowing mode. Exclusive on this ROM — fronts its own task. */
        GEOMETRY,

        /** Makes a tile visible. Inclusive, but cannot create a windowing mode. */
        LAUNCH,
    }

    data class Step(
        val phase: Phase,
        /**
         * Position in the preset's ordered windows — not in [steps].
         *
         * Pass timing is measured from it, and a retry pass has to stagger identically to the
         * initial one: sending several CHANGE_WINDOW broadcasts back to back does not reinforce
         * a layout, because each ends in `startActivityFromRecents` and fronts its own task, so
         * a burst just thrashes the stack and leaves the last package on top. `[RUNTIME]`
         * 2026-07-31 on the vehicle.
         */
        val index: Int,
        val packageName: String,
        val bounds: Rect,
        val windowMode: Int,
        /** The task is already alive: reposition it, never send it a MAIN intent. */
        val preserveLive: Boolean,
        /** Bring the task forward as well as moving it. */
        val bringToFront: Boolean,
        /** Deep link to fire before the task is positioned, so it exists first. */
        val launchIntentUri: String?,
        /** Milliseconds from the start of the placement phase. */
        val offsetMs: Long,
    )
}

/**
 * Turns a preset into a [LayoutPlan].
 *
 * Pure: rects and a launchability predicate in, a plan out. No Context, no Handler, no window
 * controller — so every decision here can be asserted directly.
 */
object LayoutPlanner {

    /**
     * @param area the usable display area, which tiles are measured against
     * @param fullDisplay the whole display, where a tiled layout parks stale windows
     * @param preserveLive packages whose live task must be repositioned rather than relaunched
     * @param isLaunchable whether a package is installed with a launcher activity
     */
    fun plan(
        preset: LayoutPreset,
        area: Rect,
        fullDisplay: Rect,
        preserveLive: Set<String>,
        isLaunchable: (String) -> Boolean,
    ): LayoutPlan {
        val ordered = preset.windows.sortedBy { it.focusOrder }
        val anchored = preset.kind == PresetKind.ANCHORED

        // The floating app's pixel bounds. An anchored preset carries exactly this one foreign
        // window; it is both where stale apps get parked and what the panel takes the
        // complement of.
        val floating = ordered.firstOrNull { it.packageName != WitsPackages.SELF }
        val floatingBounds = floating?.bounds?.toPixels(area)

        // Stale windows would otherwise keep floating above the new layout: a freeform task
        // always draws over fullscreen ones, and the vendor hook has no "close window" verb.
        // Behind the floating tile for an anchored layout — the panel is a tile now, so a
        // fullscreen park would fill the screen — and fullscreen for a tiled one.
        val park = if (anchored && floatingBounds != null) {
            LayoutPlan.Park(floatingBounds, WitsWindowMode.FREEFORM)
        } else {
            LayoutPlan.Park(fullDisplay, WitsWindowMode.FULLSCREEN)
        }

        // The panel is not one of an anchored preset's windows, so its bounds are computed
        // here — and kept, because the verification has to expect it too.
        val panelBounds = if (anchored && floating != null) {
            LayoutGeometry.panelComplement(floating.bounds, area)
        } else {
            null
        }

        val skipped = mutableListOf<String>()
        val steps = mutableListOf<LayoutPlan.Step>()
        val expected = mutableListOf<ExpectedTile>()
        val launchBase = LayoutSchedule.launchPhaseStart(ordered.size)

        ordered.forEachIndexed { index, window ->
            if (!isLaunchable(window.packageName)) {
                skipped += window.packageName
                return@forEachIndexed
            }
            val pixels = window.bounds.toPixels(area)
            val preserve = window.packageName in preserveLive

            // The Cockpit's floating app must come to the FRONT of its tile — one that is
            // hidden (behind the previous app, or behind the launcher after the vendor Home
            // button) would otherwise just be resized in place and stay hidden. True on a
            // route-safe reassert too: place()'s bring-to-front is a plain startActivity, which
            // brings the EXISTING task forward with no relaunch, so a live Maps route survives,
            // and is non-exclusive, so the panel tile stays visible. `[RUNTIME]` 2026-08-12 —
            // probed on the unit; `startActivityFromRecents` (tried in 732c089) is exclusive
            // and hid the panel, so it was reverted.
            steps += LayoutPlan.Step(
                phase = LayoutPlan.Phase.GEOMETRY,
                index = index,
                packageName = window.packageName,
                bounds = pixels,
                windowMode = window.windowMode,
                preserveLive = preserve,
                bringToFront = anchored && window.packageName != WitsPackages.SELF,
                launchIntentUri = window.launchIntentUri,
                offsetMs = index * LayoutSchedule.GEOMETRY_DELAY_MS,
            )

            // A package that was already live gets no visibility step: the geometry phase has
            // repositioned it, and launching it would send a MAIN intent that could reset the
            // app — an active route, an open menu. See LayoutEngine.reassert.
            if (!preserve) {
                steps += LayoutPlan.Step(
                    phase = LayoutPlan.Phase.LAUNCH,
                    index = index,
                    packageName = window.packageName,
                    bounds = pixels,
                    windowMode = window.windowMode,
                    preserveLive = false,
                    bringToFront = false,
                    launchIntentUri = null,
                    offsetMs = launchBase + index * LayoutSchedule.LAUNCH_DELAY_MS,
                )
            }

            expected += ExpectedTile(window.packageName, pixels)
        }

        if (panelBounds != null) expected += ExpectedTile(WitsPackages.SELF, panelBounds)

        return LayoutPlan(
            keep = ordered.map { it.packageName }.toSet(),
            park = park,
            panelBounds = panelBounds,
            steps = steps,
            expected = expected,
            skipped = skipped,
            windowCount = ordered.size,
            anchored = anchored,
        )
    }
}
