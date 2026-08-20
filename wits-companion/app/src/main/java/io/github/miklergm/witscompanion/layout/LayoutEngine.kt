package io.github.miklergm.witscompanion.layout

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.safety.ActionRateLimiter
import io.github.miklergm.witscompanion.safety.GuardVerdict
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.safety.Trigger
import io.github.miklergm.witscompanion.wits.PrivilegedWindowController
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsWindowController
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import java.util.concurrent.atomic.AtomicLong

/**
 * Applies a [LayoutPreset] by sending one CHANGE_WINDOW broadcast per tile.
 *
 * Ordering rules (docs/window-management.md §6):
 *  - tiles are applied in ascending [LayoutWindow.focusOrder],
 *  - so the highest focusOrder is applied last and ends up focused,
 *  - a deep link, when present and the task is missing, is fired first.
 *
 * Retries are bounded — never an infinite loop.
 */
class LayoutEngine(
    private val appContext: Context,
    private val windowController: WitsWindowController,
    private val reverseGuard: ReverseGuard,
    private val rateLimiter: ActionRateLimiter,
    private val logger: EventLogger? = null,
) {

    sealed interface Result {
        data class Applied(val windows: Int, val warnings: List<String>) : Result
        data class Invalid(val errors: List<String>) : Result
        data class Refused(val reason: String) : Result
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRetries = 0

    /**
     * Every scheduled broadcast belongs to a generation. A new apply, a cancel, or an
     * unsafe vehicle state bumps it, and any callback from an older generation becomes a
     * no-op.
     *
     * Without this, a retry queued 1.3 s ago still fires after the driver has engaged
     * reverse, switched to the OEM screen, or chosen a different preset — moving windows
     * at exactly the wrong moment. Cancelling the handler queue alone is not enough,
     * because a callback can already be running when the state changes.
     */
    private val generation = AtomicLong(0)

    /** Latest car state, so delayed sends can re-check safety at fire time. */
    @Volatile
    private var latestState: CarState = CarState()

    /** Packages placed by the last successful apply, so the next one can park them. */
    @Volatile
    private var lastAppliedPackages: Set<String> = emptySet()

    /**
     * True while WE are the one driving the screen — set by an [apply], cleared by
     * [unwindowTiles] (Exit / reset). The post-apply verification must never "repair" a layout
     * the user deliberately left: after Exit the last preset is still remembered, so without this
     * a verification would helpfully bring the Cockpit back and fight the exit.
     */
    @Volatile
    private var layoutOwned: Boolean = false

    /** One tile an apply intended to place, captured for [verifyPlacement]. */
    private data class ExpectedTile(val packageName: String, val bounds: android.graphics.Rect)

    /**
     * @param trigger USER for a button press, AUTOMATIC for a restore
     * @param retries how many bounded retries to schedule (0..2)
     */
    /**
     * Packages that already have a live task, so a restore can tell "reposition" from
     * "launch". Only meaningful on the privileged path; empty otherwise, which makes the
     * caller fall back to the normal apply — no worse than before.
     */
    fun livePackages(): Set<String> =
        windowController.rootTasks().mapNotNull { it.packageName }.toSet()

    /**
     * Route-safe restore: re-assert [preset] **without disturbing apps that are still
     * running**.
     *
     * This is the "continue where you left off" path. A window whose task is already alive
     * is only repositioned — on the privileged path `resizeTask` moves it with no MAIN
     * intent, so Google Maps keeps its active route and any open menu exactly as they were.
     * Only a window with no live task is launched, and then there is no prior route to
     * preserve anyway.
     *
     * Used for automatic restores (a deep-sleep wake, ACC on). An explicit user Apply still
     * goes through [apply], which may relaunch — the user asked for a fresh layout.
     */
    fun reassert(preset: LayoutPreset, state: CarState): Result {
        val live = livePackages()
        return apply(preset, state, Trigger.AUTOMATIC, preserveLive = live)
    }

    /**
     * @param verifyAttempt how many post-apply verifications already corrected this layout. 0 for a
     *   normal apply; [verifyPlacement] passes attempt+1 when it re-asserts, which is what bounds
     *   the correction loop.
     */
    fun apply(
        preset: LayoutPreset,
        state: CarState,
        trigger: Trigger,
        retries: Int = DEFAULT_RETRIES,
        preserveLive: Set<String> = emptySet(),
        verifyAttempt: Int = 0,
    ): Result {
        // 1. Validate before touching anything.
        val issues = LayoutValidator.validate(preset)
        if (LayoutValidator.hasErrors(issues)) {
            val errors = issues.filter { it.severity == LayoutIssue.Severity.ERROR }.map { it.message }
            logger?.log("layout", "apply", extras = mapOf("preset" to preset.id), result = "invalid:$errors")
            return Result.Invalid(errors)
        }

        // 2. Safety: never re-layout over the reverse camera.
        when (val verdict = reverseGuard.check(state, trigger)) {
            is GuardVerdict.Blocked -> {
                logger?.log(
                    "layout", "apply",
                    extras = mapOf("preset" to preset.id, "trigger" to trigger.name),
                    result = "blocked:${verdict.reason}",
                )
                return Result.Refused(verdict.reason)
            }
            GuardVerdict.Allowed -> Unit
        }

        // 3. Rate limit — automatic triggers only. A deliberate user tap must never be
        //    refused: rapid taps used to raise "rate limit" toasts and, because each apply
        //    supersedes the previous one mid-sequence, could leave a half-applied layout.
        //    The generation token already makes the last tap win cleanly, so user applies
        //    are not throttled; the limiter still guards against automatic-restore loops.
        if (trigger != Trigger.USER) {
            when (val verdict = rateLimiter.check(
                ActionRateLimiter.KEY_LAYOUT, ActionRateLimiter.LAYOUT_APPLY
            )) {
                is GuardVerdict.Blocked -> {
                    logger?.log("layout", "apply", result = "rate_limited:${verdict.reason}")
                    return Result.Refused(verdict.reason)
                }
                GuardVerdict.Allowed -> Unit
            }
        }

        val warnings = issues.filter { it.severity == LayoutIssue.Severity.WARNING }
            .map { it.message }
            .toMutableList()

        val area = windowController.usableArea(appContext)
        val ordered = preset.windows.sortedBy { it.focusOrder }

        // Invalidate anything still queued from an earlier apply.
        val myGeneration = generation.incrementAndGet()
        latestState = state
        applyTrigger = trigger
        handler.removeCallbacksAndMessages(RETRY_TOKEN)

        // The floating app's pixel bounds (anchored presets have exactly this one foreign
        // window), used both to park stale apps behind it and to reserve the panel strip.
        val floatingBounds = ordered.firstOrNull { it.packageName != WitsPackages.SELF }
            ?.bounds?.toPixels(area)

        // Windows left over from the previous layout would keep floating above the new one,
        // because a freeform task always draws over fullscreen tasks and the vendor hook has
        // no "close window" verb. Park them out of the way — behind the Cockpit's floating
        // tile for an anchored layout (the panel is a tile now, so a fullscreen park would
        // fill the screen), or fullscreen for a tiled layout.
        val parked = if (preset.kind == PresetKind.ANCHORED && floatingBounds != null) {
            parkStaleWindows(
                ordered.map { it.packageName }.toSet(), floatingBounds,
                WitsWindowMode.FREEFORM, myGeneration,
            )
        } else {
            parkStaleWindows(
                ordered.map { it.packageName }.toSet(),
                windowController.fullDisplayArea(appContext), WitsWindowMode.FULLSCREEN,
                myGeneration,
            )
        }

        // An anchored preset brings the companion up as the panel tile beside the map. The panel is
        // NOT one of the preset's windows (an anchored preset carries only the foreign app), so its
        // bounds are computed here — and hoisted so the verification below can expect it too.
        val panelBounds =
            if (preset.kind == PresetKind.ANCHORED && floatingBounds != null) {
                panelComplement(ordered.first { w -> w.packageName != WitsPackages.SELF }.bounds, area)
            } else {
                null
            }
        var offset = 0L
        if (preset.kind == PresetKind.ANCHORED) {
            handler.postDelayed(
                { if (stillValid(myGeneration, "anchor", WitsPackages.SELF)) bringAnchorToFront(panelBounds) },
                parked * PARK_DELAY_MS,
            )
            offset = parked * PARK_DELAY_MS + ANCHOR_SETTLE_MS
        } else if (parked > 0) {
            offset = parked * PARK_DELAY_MS
        }

        ordered.forEachIndexed { index, window ->
            if (!windowController.isLaunchable(window.packageName)) {
                warnings += "${window.packageName} is not installed or has no launcher activity"
                logger?.log(
                    "layout", "skip_window", window.packageName, result = "not_launchable"
                )
                return@forEachIndexed
            }

            // Deep link first, so the task exists before we reposition it.
            window.launchIntentUri?.let { uri ->
                windowController.startDeepLink(uri, window.packageName)
            }

            val pixels = window.bounds.toPixels(area)

            // Phase 1 — geometry for every tile, before any of them is launched. A
            // preserved-live tile is repositioned only if it can be moved in place; a live
            // task in another mode is left untouched rather than relaunched.
            val preserve = window.packageName in preserveLive
            // The Cockpit's floating app must come to the FRONT of its tile — otherwise one that is
            // hidden (behind the previous app, or behind the launcher after the vendor Home button)
            // just gets resized in place and stays hidden. This holds on a route-safe reassert too:
            // place()'s bring-to-front is a plain startActivity (launchIntoFreeform), which brings the
            // EXISTING task forward — "brought to the front", NO relaunch, so a live Maps route
            // survives — and is NON-exclusive, so the panel tile stays visible. `[RUNTIME]`
            // 2026-08-12: probed on the head unit — `am start` fronts the map over the launcher
            // without hiding the panel; `startActivityFromRecents` (tried in 732c089) is exclusive
            // and hid the panel, so it was reverted.
            val bringToFront = preset.kind == PresetKind.ANCHORED &&
                window.packageName != WitsPackages.SELF
            handler.postDelayed(
                {
                    if (stillValid(myGeneration, "geometry", window.packageName)) {
                        windowController.applyWindow(
                            WitsWindowController.WindowRequest(
                                window.packageName, pixels, window.windowMode
                            ),
                            preserveLive = preserve,
                            bringToFront = bringToFront,
                        )
                    }
                },
                offset + index * GEOMETRY_DELAY_MS,
            )

            // Phase 2 — make every tile visible, after all geometry has landed.
            // A package that was already live is skipped here: the geometry phase has
            // repositioned it in place, and launching it would send a MAIN intent that
            // could reset the app (an active Maps route, an open menu). See reassert().
            if (window.packageName in preserveLive) {
                logger?.log("layout", "preserve_live", window.packageName, result = "no_relaunch")
                return@forEachIndexed
            }
            handler.postDelayed(
                {
                    if (stillValid(myGeneration, "make_visible", window.packageName)) {
                        windowController.launchPackage(window.packageName, pixels, window.windowMode)
                    }
                },
                offset + launchPhaseStart(ordered.size) + index * LAUNCH_DELAY_MS,
            )
        }

        lastAppliedPackages = ordered.map { it.packageName }.toSet()
        layoutOwned = true
        rateLimiter.record(ActionRateLimiter.KEY_LAYOUT)
        logger?.log(
            category = "layout", action = "apply",
            extras = mapOf(
                "preset" to preset.id,
                "windows" to ordered.size,
                "trigger" to trigger.name,
                "area" to area.flattenToString(),
            ),
            result = "sent", confidence = "HYP",
        )

        scheduleRetries(preset, area, ordered, retries.coerceIn(0, MAX_RETRIES), myGeneration, preserveLive)

        // What this apply intends the screen to look like — the yardstick for the verification.
        // Captured from the apply itself rather than derived from stored state, so "panel full-screen"
        // is only ever wrong when THIS apply meant it to be a tile (the hidden state applies its own
        // full-screen panel and is verified against that).
        val expected = buildList {
            ordered.forEach { w ->
                if (windowController.isLaunchable(w.packageName)) {
                    add(ExpectedTile(w.packageName, w.bounds.toPixels(area)))
                }
            }
            if (panelBounds != null) add(ExpectedTile(WitsPackages.SELF, panelBounds))
        }
        scheduleVerification(preset, expected, ordered.size, myGeneration, verifyAttempt)

        // Report what was actually dispatched, not what the preset asked for. `expected` is
        // exactly the set of tiles this apply put on screen — launchable windows, plus the
        // panel an anchored preset does not carry as a window — so a preset whose apps are
        // all missing no longer reports a cheerful "Applied 2 window(s)" while nothing moved.
        if (expected.isEmpty()) {
            logger?.log(
                "layout", "apply", extras = mapOf("preset" to preset.id),
                result = "nothing_launchable",
            )
            return Result.Refused(
                "nothing to place: no app in this layout is installed with a launcher activity"
            )
        }

        return Result.Applied(expected.size, warnings)
    }

    /**
     * Bounded re-application.
     *
     * Each retry pass **staggers its windows exactly like the initial pass**. Sending
     * several `CHANGE_WINDOW` broadcasts back to back does not reinforce a layout: every
     * one of them ends in `startActivityFromRecents`, which brings that task to the
     * front, so a burst just thrashes the stack and leaves the last package on top.
     * [RUNTIME] — observed on the vehicle 2026-07-31.
     *
     * Passes are also offset past the end of the initial pass so the two never overlap.
     * Never loops.
     */
    private fun scheduleRetries(
        preset: LayoutPreset,
        area: android.graphics.Rect,
        ordered: List<LayoutWindow>,
        retries: Int,
        myGeneration: Long,
        preserveLive: Set<String>,
    ) {
        pendingRetries = retries
        if (retries <= 0 || ordered.isEmpty()) return

        val n = ordered.size

        RETRY_DELAYS_MS.take(retries).forEachIndexed { attempt, gap ->
            val passStart = passDuration(n) + gap
            ordered.forEachIndexed { index, window ->
                // A preserved-live window is never relaunched, not even on a retry: it was
                // already there, so there is nothing to repair and a launch would only risk
                // resetting it.
                if (window.packageName in preserveLive) return@forEachIndexed
                val pixels = window.bounds.toPixels(area)
                // What a retry does depends on the path:
                //  - Privileged: re-assert the geometry with resizeTask. Some apps (Spotify)
                //    launch at the given bounds and then grow themselves to full width; a
                //    delayed resizeTask pulls the now-visible tile back to where it belongs.
                //    `launchPackage` is a no-op when privileged, so without this the retry
                //    did nothing and the tile stayed stretched.
                //  - Unprivileged: launch only. CHANGE_WINDOW is exclusive — re-sending it
                //    would hide the other tiles and flash the layout — whereas an inclusive
                //    launch carries the bounds itself and repairs a tile that never appeared.
                handler.postDelayed({
                    if (stillValid(myGeneration, "retry_visible", window.packageName) &&
                        windowController.isLaunchable(window.packageName)
                    ) {
                        // With in-place movement available, applyWindow repositions without
                        // fronting; without it the only way to re-place a tile is to launch it.
                        if (windowController.taskResizer != null) {
                            windowController.applyWindow(
                                WitsWindowController.WindowRequest(window.packageName, pixels, window.windowMode)
                            )
                        } else {
                            windowController.launchPackage(window.packageName, pixels, window.windowMode)
                        }
                    }
                }, RETRY_TOKEN, passStart + index * LAUNCH_DELAY_MS)
            }
            handler.postDelayed({
                if (myGeneration == generation.get()) {
                    logger?.log(
                        "layout", "retry",
                        extras = mapOf("preset" to preset.id, "attempt" to (attempt + 1)),
                        result = "sent",
                    )
                }
            }, RETRY_TOKEN, passStart)
        }
    }

    /**
     * The absolute times, in ms from `apply()`, at which each broadcast is scheduled.
     * Exposed for tests so the schedule can be asserted without a device.
     */
    fun scheduleFor(windowCount: Int, retries: Int): List<Long> {
        if (windowCount <= 0) return emptyList()
        val out = mutableListOf<Long>()
        // Initial pass: geometry for every tile, then a launch for every tile.
        repeat(windowCount) { i -> out += i * GEOMETRY_DELAY_MS }
        val launchBase = launchPhaseStart(windowCount)
        repeat(windowCount) { i -> out += launchBase + i * LAUNCH_DELAY_MS }
        // Retry passes: launches only, so a retry never hides a placed tile.
        RETRY_DELAYS_MS.take(retries.coerceIn(0, MAX_RETRIES)).forEach { gap ->
            val base = passDuration(windowCount) + gap
            repeat(windowCount) { i -> out += base + i * LAUNCH_DELAY_MS }
        }
        return out
    }

    /**
     * When the visibility phase starts, relative to the start of a pass: after every
     * `CHANGE_WINDOW` has been sent, plus a settle gap.
     *
     * The two phases must not interleave. `CHANGE_WINDOW` takes the vendor hook's warm
     * path (`getFreeformTaskId` → `startActivityFromRecents`), which brings its own task
     * forward and drops every other freeform task to `visibleRequested=false`. A plain
     * launch is inclusive — it shows a task without hiding its neighbours — but it does
     * not set the windowing mode for a task that does not exist yet.
     *
     * So: all geometry first (exclusive, but only the last one's visibility survives),
     * then all launches (inclusive, and they inherit the bounds already set).
     * `[RUNTIME]` 2026-07-31, verified by dumpsys — see research/window-debug/.
     */
    private fun launchPhaseStart(windowCount: Int): Long =
        (windowCount - 1).coerceAtLeast(0) * GEOMETRY_DELAY_MS + PHASE_GAP_MS

    /** Total length of one full pass (geometry phase + visibility phase). */
    private fun passDuration(windowCount: Int): Long =
        launchPhaseStart(windowCount) + (windowCount - 1).coerceAtLeast(0) * LAUNCH_DELAY_MS

    /**
     * Schedules the post-apply verification — the "did what I just did actually take?" check.
     *
     * This is **not** a standing watchdog: it only ever runs in a bounded window right after an
     * apply, so it cannot fight the user who later opens something else, nor the vendor stack. It
     * exists because the blind [scheduleRetries] pass fires too early and unconditionally: on a cold
     * boot the autostart apply lands while **freeform is not ready yet**, so the panel comes up
     * full-screen and the floating app is never placed (`[RUNTIME]` 2026-08-11/14, 2 of 2 cold
     * boots). The verification is the *conditional* retry with a longer horizon.
     *
     * Privileged path only — it needs `getAllRootTaskInfos` to observe anything.
     */
    private fun scheduleVerification(
        preset: LayoutPreset,
        expected: List<ExpectedTile>,
        windowCount: Int,
        myGeneration: Long,
        attempt: Int,
    ) {
        // Verification needs to read live task state; without that it could send corrections
        // but never find out whether the layout took.
        if (windowController.taskObserver == null || expected.isEmpty()) return
        if (attempt >= VERIFY_DELAYS_MS.size) return
        // Measured from the end of the pass, like the retries, so the two never overlap.
        val delay = passDuration(windowCount) + VERIFY_DELAYS_MS[attempt]
        handler.postDelayed(
            { verifyPlacement(preset, expected, myGeneration, attempt) },
            RETRY_TOKEN,
            delay,
        )
    }

    /**
     * Compares the live task state with what the apply intended and, on a mismatch, re-asserts the
     * layout once more (bounded by [VERIFY_DELAYS_MS]).
     *
     * The correction is a **route-safe re-assert**, not a fresh apply: a live app is repositioned and
     * fronted rather than relaunched, so a running Maps route survives a repair. That matters because
     * this can fire on a cold boot while the driver is already looking at the screen.
     */
    private fun verifyPlacement(
        preset: LayoutPreset,
        expected: List<ExpectedTile>,
        myGeneration: Long,
        attempt: Int,
    ) {
        // Superseded by a newer apply / cancelled (cancelPending bumps the generation — that is also
        // how opening the config screen calls this off).
        if (myGeneration != generation.get()) return
        // The user took the screen back (Exit / reset): the last preset is still remembered, but it
        // is no longer ours to repair.
        if (!layoutOwned) {
            logger?.log("layout", "verify", result = "skipped:not_owned")
            return
        }
        // Never re-lay windows over the reverse camera.
        if (!reverseGuard.check(latestState, Trigger.AUTOMATIC).isAllowed) {
            logger?.log("layout", "verify", result = "skipped:reverse")
            return
        }

        val tasks = windowController.rootTasks()
        val wrong = expected.filter { misplaced(it, tasks) }
        if (wrong.isEmpty()) {
            logger?.log(
                "layout", "verify",
                extras = mapOf("preset" to preset.id, "attempt" to attempt, "tiles" to expected.size),
                result = "ok",
            )
            return
        }

        Log.i(TAG, "verify: ${preset.id} attempt=$attempt misplaced=${wrong.map { it.packageName }} -> re-assert")
        logger?.log(
            "layout", "verify",
            extras = mapOf(
                "preset" to preset.id,
                "attempt" to attempt,
                "misplaced" to wrong.joinToString(",") { it.packageName },
            ),
            result = "correcting",
        )
        // Same correction the user's "tap it again" performs, which is known to work.
        apply(
            preset, latestState, Trigger.AUTOMATIC,
            preserveLive = livePackages(),
            verifyAttempt = attempt + 1,
        )
    }

    /**
     * Coarse placement check for one tile — deliberately **not** an exact geometry match.
     *
     * Only gross failures count: no task at all, not a freeform window (the cold-boot symptom: the
     * panel stuck full-screen), invisible (left behind the launcher), or sitting on the wrong side of
     * the display. Exact bounds are left to the retry pass and the tiles' own self-resize; demanding
     * pixel accuracy here would fight apps that legitimately resize themselves (Spotify) and cause
     * endless corrections.
     */
    private fun misplaced(tile: ExpectedTile, tasks: List<PrivilegedWindowController.TaskSnapshot>): Boolean {
        val task = tasks.firstOrNull { it.packageName == tile.packageName } ?: return true
        if (task.windowingMode != WitsWindowMode.FREEFORM) return true
        if (!task.visible) return true
        // Wrong half of the screen: centres further apart than half the intended tile width.
        val slack = (tile.bounds.width() / 2).coerceAtLeast(MIN_CENTRE_SLACK_PX)
        return kotlin.math.abs(task.bounds.centerX() - tile.bounds.centerX()) > slack
    }

    /**
     * Parks freeform windows that are not part of the incoming layout, to [parkBounds] in
     * [parkMode].
     *
     * There is no way to close a window through the vendor hook, and killing the process would
     * stop playback, so a stale window is instead moved out of the way.
     *
     *  - **Tiled layouts** park to a fullscreen task, which whatever is placed on top occludes.
     *  - **Anchored (Cockpit) layouts** must NOT park to fullscreen: the panel is now a freeform
     *    *tile*, not a fullscreen anchor, so a full-size window has nothing covering it and
     *    fills the screen (`[RUNTIME]` 2026-08-03 — switching the floating app left the previous
     *    one stretched full-screen over the panel). They park to the **floating tile's bounds**
     *    instead, so a switched-away app sits hidden behind the new one in the same tile.
     *
     * Every delayed mutation here is gated on [myGeneration] and tagged with `RETRY_TOKEN`, like
     * the placement sends: parking and removal outlive the call that scheduled them, so without
     * the gate a superseded apply could still remove or reposition tiles belonging to the layout
     * that replaced it.
     *
     * @return how many packages were parked, so the caller can offset what follows
     */
    private fun parkStaleWindows(
        keep: Set<String>,
        parkBounds: android.graphics.Rect,
        parkMode: Int,
        myGeneration: Long,
    ): Int {
        // Everything to clear away: what we last placed, plus — on the privileged path —
        // any live freeform tile at all that is not part of the incoming layout. Rapid
        // taps can leave freeform tasks we never recorded in lastAppliedPackages; without
        // this they float over the new layout (e.g. a leftover fullscreen Spotify when the
        // Cockpit is opened). SELF is never parked: the companion is the anchor/panel.
        val liveTasks = windowController.rootTasks()
            .filter { it.windowingMode == WitsWindowMode.FREEFORM }
        val liveFreeform = liveTasks.mapNotNull { it.packageName }
        val stale = (lastAppliedPackages + liveFreeform - keep) - WitsPackages.SELF
        if (stale.isEmpty()) return 0

        // Tiled layouts (parkMode = FULLSCREEN) have no floating tile to hide a stale app behind,
        // so the old path tried to un-freeform it — but `setTaskWindowingMode` is absent on this
        // ROM, so `place()` fell through and *re-launched* the app as yet another freeform tile.
        // Switching presets then piled up windows (`[RUNTIME]` 2026-08-08: 5 freeform tasks). On
        // the privileged path, REMOVE the stale tasks outright instead (they are not in the new
        // layout); the app is relaunched clean when a preset that includes it is next applied.
        val remover = windowController.taskRemover
        if (parkMode == WitsWindowMode.FULLSCREEN && remover != null) {
            // A tiled layout has NO companion window, so also clear our own leftover Cockpit tiles
            // (the config + the panel), not just stale foreign apps — otherwise they stay on top
            // and cover the two tiles ("Maps and Chrome didn't open because it was Cockpit before",
            // `[RUNTIME]` 2026-08-08). Remove every live freeform task that is not in the new layout,
            // SELF included. The config task that triggered this apply is torn down after its call
            // returns (the removal is posted), like the Settings button.
            val toRemove = liveTasks.filter { it.packageName !in keep }
            toRemove.forEachIndexed { index, task ->
                val pkg = task.packageName ?: "task:${task.taskId}"
                handler.postDelayed(
                    { if (stillValid(myGeneration, "remove_stale", pkg)) remover.remove(task.taskId) },
                    RETRY_TOKEN,
                    index * PARK_DELAY_MS,
                )
            }
            logger?.log(
                "layout", "remove_stale",
                extras = mapOf(
                    "packages" to toRemove.mapNotNull { it.packageName }.joinToString(","),
                    "count" to toRemove.size,
                ),
            )
            return toRemove.size
        }

        // Anchored (freeform park to the floating tile) / unprivileged: move stale apps in place.
        stale.forEachIndexed { index, pkg ->
            if (!windowController.isLaunchable(pkg)) return@forEachIndexed
            handler.postDelayed({
                if (stillValid(myGeneration, "park_stale", pkg)) {
                    windowController.applyWindow(
                        WitsWindowController.WindowRequest(pkg, parkBounds, parkMode)
                    )
                }
            }, RETRY_TOKEN, index * PARK_DELAY_MS)
        }
        logger?.log(
            "layout", "park_stale",
            extras = mapOf(
                "packages" to stale.joinToString(","), "count" to stale.size,
                "mode" to WitsWindowMode.name(parkMode),
            ),
        )
        return stale.size
    }

    /**
     * Brings the companion's own [DashboardActivity] up as the Cockpit panel — as a **freeform
     * tile beside the map**, not a fullscreen anchor behind it, when [panelBounds] is given.
     *
     * Why a tile and not fullscreen: a fullscreen panel *overlaps* the floating map, so any tap
     * on a panel control focuses the panel task and the framework raises it over the map,
     * hiding it (`[RUNTIME]` 2026-08-02 — the "map disappears when I tap brightness/hotspot"
     * bug). Two non-overlapping freeform tiles never occlude each other on focus, so placing
     * the panel beside the map removes the bug at the root — no re-raise, no flicker.
     * [DashboardActivity]'s reservation logic already fills the window when it is not
     * display-wide, so the panel simply fills its tile.
     *
     * Falls back to a plain (fullscreen) start when no bounds are known.
     */
    private fun bringAnchorToFront(panelBounds: android.graphics.Rect? = null) {
        runCatching {
            val intent = android.content.Intent(
                appContext,
                io.github.miklergm.witscompanion.ui.DashboardActivity::class.java,
            ).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            val options = if (panelBounds != null && !panelBounds.isEmpty) {
                val o = android.app.ActivityOptions.makeBasic().setLaunchBounds(panelBounds)
                // Hidden setter; resolves under the platform signature, best-effort otherwise.
                runCatching {
                    android.app.ActivityOptions::class.java
                        .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        .invoke(o, WitsWindowMode.FREEFORM)
                }
                o.toBundle()
            } else {
                null
            }
            appContext.startActivity(intent, options)
            logger?.log(
                "layout", "anchor_to_front",
                extras = mapOf("bounds" to (panelBounds?.flattenToString() ?: "fullscreen")),
                result = "sent",
            )
        }.onFailure {
            logger?.log("layout", "anchor_to_front", result = "error:${it.javaClass.simpleName}")
        }
    }

    /**
     * The panel's pixel bounds for an anchored layout: the strip the map does **not** cover.
     * Null when the map is not flush to an edge (then the panel stays fullscreen).
     */
    private fun panelComplement(mapBounds: NormalizedBounds, area: android.graphics.Rect): android.graphics.Rect? {
        val panel = when {
            mapBounds.left <= 0.01f && mapBounds.right < 0.99f ->
                NormalizedBounds(mapBounds.right, 0f, 1f, 1f)   // map left  → panel right
            mapBounds.right >= 0.99f && mapBounds.left > 0.01f ->
                NormalizedBounds(0f, 0f, mapBounds.left, 1f)    // map right → panel left
            else -> return null
        }
        return panel.toPixels(area)
    }

    /**
     * The Cockpit's **floating-app (left) tile** in normalized bounds: `[0,0,split,1]`, mirrored to
     * `[1-split,0,1,1]` when [swapped]. The single split→geometry primitive both cockpit-bounds
     * helpers (and, via the panel's [DashboardActivity.reservation], the black-strip reservation)
     * derive from, so the app tile, the panel complement and the reserved strip can never disagree.
     */
    private fun appTileNormalized(split: Float, swapped: Boolean): NormalizedBounds {
        val f = split.coerceIn(LayoutPreset.MIN_SPLIT, LayoutPreset.MAX_SPLIT)
        return if (swapped) NormalizedBounds(1f - f, 0f, 1f, 1f) else NormalizedBounds(0f, 0f, f, 1f)
    }

    /**
     * The pixel bounds the Cockpit panel window should occupy: the complement tile beside the
     * floating app when one is showing, or the whole display when the app is hidden. The panel
     * uses this to resize **its own** task ([DashboardActivity.ensurePanelBounds]) because a
     * relaunch's `setLaunchBounds` is ignored once the task exists, so it would otherwise stay
     * full-screen. Same geometry the anchored [apply] places the app with.
     */
    fun cockpitPanelBounds(split: Float, swapped: Boolean, hidden: Boolean): android.graphics.Rect {
        val full = windowController.fullDisplayArea(appContext)
        if (hidden) return full
        val area = windowController.usableArea(appContext)
        return panelComplement(appTileNormalized(split, swapped), area) ?: full
    }

    /**
     * The pixel bounds of the Cockpit's **floating-app (left) tile** — the slot the map lives in.
     * The Settings gear launches our config ([MainActivity]) into these bounds so it sits in the
     * left tile beside the panel, instead of leaving the Cockpit for a full-screen screen (which
     * fought the un-window / autostart machinery). Complement of [cockpitPanelBounds].
     */
    fun cockpitAppBounds(split: Float, swapped: Boolean): android.graphics.Rect {
        val area = windowController.usableArea(appContext)
        return appTileNormalized(split, swapped).toPixels(area)
    }

    /**
     * Gate every delayed send: the generation must still be current **and** the vehicle
     * must still be safe *at fire time*, not merely when the layout was requested.
     */
    private fun stillValid(myGeneration: Long, phase: String, pkg: String): Boolean {
        if (myGeneration != generation.get()) {
            logger?.log(
                "layout", "send_skipped", pkg,
                extras = mapOf("phase" to phase, "reason" to "superseded"),
            )
            return false
        }
        // Re-check with the ORIGINAL trigger, not always AUTOMATIC. A deliberate user tap
        // should still place its windows when the reverse state is merely *unknown* (common
        // — the property is not always readable); it must abort only if reverse is actually
        // engaged. Downgrading every fire-time check to AUTOMATIC dropped user applies
        // whenever reverse was unknown (e.g. no CAN data), which read as "nothing happened".
        // fail-closed on unknown is preserved for automatic restores.
        val verdict = reverseGuard.check(latestState, applyTrigger)
        if (!verdict.isAllowed) {
            logger?.log(
                "layout", "send_skipped", pkg,
                extras = mapOf("phase" to phase, "reason" to (verdict.reasonOrNull ?: "unsafe")),
            )
            cancelPending()
            return false
        }
        return true
    }

    /** Trigger of the apply whose delayed sends are in flight, for the fire-time re-check. */
    @Volatile
    private var applyTrigger: Trigger = Trigger.AUTOMATIC

    /**
     * Feed vehicle state so delayed sends can re-check safety, and abort immediately if
     * reverse becomes active mid-sequence.
     */
    fun onCarState(state: CarState) {
        latestState = state
        if (state.reverseActive == true && pendingRetries > 0) {
            logger?.log("layout", "cancel_pending", result = "reverse_engaged")
            cancelPending()
        }
    }

    /**
     * Cancels everything still queued and invalidates in-flight callbacks.
     * Call when reverse engages, the source leaves Android, or the user picks another
     * preset.
     */
    fun cancelPending() {
        generation.incrementAndGet()
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        handler.removeCallbacksAndMessages(null)
        pendingRetries = 0
    }

    /** Current generation; for tests and diagnostics. */
    fun currentGeneration(): Long = generation.get()

    /**
     * Puts the screen back to a clean vendor state — the one-tap "undo everything", no adb
     * and no reboot: un-window every tile and bring the home launcher to the front.
     */
    fun resetToVendorState() = unwindowTiles(thenGoHome = true)

    /**
     * Clears the Cockpit's freeform tiles so nothing keeps drawing over whatever is shown next
     * (the launcher, our config screen, or the next navigation) — the fix for both "Exit does not
     * reset the windows" and "Settings just flashes and never opens".
     *
     * On the privileged path this is a single `removeRootTasksInWindowingModes([FREEFORM])`
     * (`[RUNTIME]` 2026-08-07: `setTaskWindowingMode` throws `NoSuchMethodException` on this ROM,
     * so we cannot un-freeze a task in place — we remove the freeform tasks outright; the apps are
     * relaunched when a layout is next applied). On the unprivileged path (emulator) we fall back
     * to the per-tile CHANGE_WINDOW hook, which re-issues FULLSCREEN.
     *
     * @param thenGoHome bring the vendor launcher to the front afterwards (Exit); false when the
     *   caller shows its own screen next (Settings → MainActivity). Cancels pending work first so
     *   a delayed apply cannot re-tile behind it.
     */
    fun unwindowTiles(thenGoHome: Boolean) {
        cancelPending()
        // The user is taking the screen back: stop owning it, so no verification "repairs" the
        // layout they just exited (the last preset is still remembered).
        layoutOwned = false
        val myGeneration = generation.get()

        val bulkRemover = windowController.taskRemover
        if (bulkRemover != null) {
            val removed = bulkRemover.removeAllFreeform()
            if (thenGoHome) {
                handler.postDelayed({ if (myGeneration == generation.get()) goHome() }, ANCHOR_SETTLE_MS)
            }
            lastAppliedPackages = emptySet()
            logger?.log(
                "layout", "unwindow_tiles",
                extras = mapOf("home" to thenGoHome),
                result = removed.reasonOrNull ?: "ok",
            )
            return
        }

        // Unprivileged (emulator): no removeRootTasks reach — un-window each tile via the hook.
        val full = windowController.fullDisplayArea(appContext)
        val liveTiles = windowController.rootTasks()
            .filter { it.windowingMode == WitsWindowMode.FREEFORM }
            .mapNotNull { it.packageName }
        val tiles = (liveTiles + lastAppliedPackages).toSet()

        tiles.filter { windowController.isLaunchable(it) }.forEachIndexed { index, pkg ->
            handler.postDelayed({
                if (myGeneration == generation.get()) {
                    windowController.applyWindow(
                        WitsWindowController.WindowRequest(pkg, full, WitsWindowMode.FULLSCREEN)
                    )
                }
            }, index * PARK_DELAY_MS)
        }

        if (thenGoHome) {
            handler.postDelayed({
                if (myGeneration == generation.get()) goHome()
            }, tiles.size * PARK_DELAY_MS + ANCHOR_SETTLE_MS)
        }

        lastAppliedPackages = emptySet()
        logger?.log("layout", "unwindow_tiles", extras = mapOf("tiles" to tiles.size, "home" to thenGoHome))
    }

    /**
     * Brings the home launcher to the front with a standard HOME intent — no need to know
     * the vendor package. The vendor launcher comes up fullscreen over everything, which
     * is the clean state the reset aims for.
     */
    private fun goHome() {
        runCatching {
            appContext.startActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            logger?.log("layout", "go_home", result = "sent")
        }.onFailure {
            logger?.log("layout", "go_home", result = "error:${it.javaClass.simpleName}")
        }
    }

    /**
     * Hides the Cockpit's floating app without leaving the Cockpit: the panel grows to fill
     * the whole display, and [io.github.miklergm.witscompanion.ui.DashboardActivity]'s own
     * reservation logic paints the freed strip as its (black) background while keeping the
     * panel content at its usual proportion — the "tap the active tile to dismiss the app"
     * toggle. The previously floating app is taken out of freeform so it drops behind the
     * now full-screen panel; a later [apply] (tapping any switcher tile) floats an app again.
     *
     * @param floatingPackage the app that was floating, to un-window; null skips that step.
     */
    fun hideFloatingApp(floatingPackage: String?) {
        cancelPending()
        val myGeneration = generation.get()
        val full = windowController.fullDisplayArea(appContext)

        // 1. Get the floating app out from over the panel.
        //  - Unprivileged (emulator): un-window it (freeform → fullscreen) so it drops behind.
        //  - Privileged (this ROM): `setTaskWindowingMode` is absent, so a freeform task cannot be
        //    un-windowed in place (see PrivilegedWindowController). We do NOT try — the full-screen
        //    panel in step 2 simply covers it (it stays alive behind; the next apply parks/removes
        //    it). This is the honest version of what already happened: the old un-window call just
        //    logged a failure on this ROM.
        if (windowController.taskRemover == null &&
            floatingPackage != null && floatingPackage != WitsPackages.SELF &&
            windowController.isLaunchable(floatingPackage)
        ) {
            windowController.applyWindow(
                WitsWindowController.WindowRequest(floatingPackage, full, WitsWindowMode.FULLSCREEN)
            )
        }

        // 2. Grow the panel to the full display. Passing full bounds (not null) keeps it a
        //    freeform tile that draws over the app below; the panel then reserves the strip itself.
        handler.postDelayed(
            { if (myGeneration == generation.get()) bringAnchorToFront(full) },
            PARK_DELAY_MS,
        )
        lastAppliedPackages = setOf(WitsPackages.SELF)
        logger?.log(
            "layout", "hide_floating",
            floatingPackage ?: "none", result = "panel_full",
        )
    }

    companion object {
        /** Gap between two CHANGE_WINDOW sends inside the geometry phase. */
        const val GEOMETRY_DELAY_MS = 250L
        /** Settle time after the last CHANGE_WINDOW, before the first launch. */
        const val PHASE_GAP_MS = 600L
        /** Gap between two launches inside the visibility phase. */
        const val LAUNCH_DELAY_MS = 700L
        const val PARK_DELAY_MS = 250L
        const val ANCHOR_SETTLE_MS = 450L
        const val DEFAULT_RETRIES = 1
        const val MAX_RETRIES = 2

        /**
         * When the post-apply verification runs, measured from the END of the pass — and, by its
         * length, how many corrections are allowed (2). Deliberately later than [RETRY_DELAYS_MS]:
         * the failure it targets is "freeform was not ready yet", which the blind retries are too
         * early to catch. Each correction relaunches nothing but does re-assert, so the budget stays
         * small — an unbounded loop would fight the vendor stack.
         */
        val VERIFY_DELAYS_MS = listOf(3_000L, 8_000L)

        /** Floor for the centre tolerance, so a narrow tile still gets sane slack. */
        const val MIN_CENTRE_SLACK_PX = 120

        private const val TAG = "LayoutEngine"

        /**
         * Gaps measured from the END of the initial pass, not from apply().
         * docs/window-management.md §7.
         */
        val RETRY_DELAYS_MS = listOf(600L, 1_600L)

        private val RETRY_TOKEN = Any()
    }
}
