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
import io.github.miklergm.witscompanion.wits.TaskObservation
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsWindowController
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import java.util.concurrent.atomic.AtomicLong

/**
 * Carries out a [LayoutPreset]: the part of applying a layout that has to touch the device.
 *
 * The decisions are elsewhere, and deliberately so. This class owns a Handler, a generation
 * counter, the last known vehicle state and whether we still own the screen — none of which a
 * test can hold still — and it used to own the reasoning as well, interleaved with the
 * `postDelayed` calls that acted on it. What an apply *would* do could then only be discovered
 * by watching what it did.
 *
 *  - [LayoutPlanner] decides: which windows can be placed, where stale ones are parked, whether
 *    the panel gets a complement tile, which window is fronted, and what the result will be
 *    compared against. Pure; takes rects and a predicate.
 *  - [LayoutSchedule] decides when each send fires. Pure arithmetic.
 *  - [LayoutGeometry] decides where the two Cockpit tiles go. Pure.
 *  - [LayoutVerification] decides whether what landed matches what was intended. Pure.
 *
 * What is left here is the execution and the state that makes it safe: every delayed send is
 * gated on the generation *and* on the vehicle still being safe at fire time ([stillValid]),
 * refusals happen before anything is mutated, and retries are bounded — never a loop.
 *
 * Ordering rules (docs/window-management.md §6): tiles are placed in ascending
 * [LayoutWindow.focusOrder], so the highest ends up focused, and a deep link is fired before
 * its task is positioned.
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
     * How much cancellable placement work is still queued.
     *
     * [onCarState] first asked `pendingRetries > 0`, which is a different question — that
     * counter is zero for every delayed send that is not a retry, and [cancelPending] zeroes it
     * on the way in, so the "abort the moment reverse engages" path was inert for exactly the
     * sequence that needed it. A boolean fixed that and introduced two more: it was set by
     * [unwindowTiles] as well, so reverse during Exit cancelled the rest of the teardown —
     * the opposite of what that path wants, since tearing our windows down *uncovers* the
     * vendor screen the camera is drawn on — and it was never cleared, so every later reverse
     * logged a cancellation of nothing.
     *
     * A count, incremented at post time and decremented when the work runs, answers the
     * question that was being asked. **Teardown is deliberately not counted**: it is not
     * cancellable by this path.
     */
    private val placementsInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Posts cancellable placement work.
     *
     * [cancelPending] drops the queue without running the bodies, so it resets the count rather
     * than relying on the decrements below.
     */
    private fun postPlacement(delayMs: Long, token: Any? = null, body: () -> Unit) {
        placementsInFlight.incrementAndGet()
        val runnable = Runnable {
            try {
                body()
            } finally {
                placementsInFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        }
        if (token != null) handler.postDelayed(runnable, token, delayMs)
        else handler.postDelayed(runnable, delayMs)
    }

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

    /**
     * Packages that already have a live task, so a restore can tell "reposition" from
     * "launch". Only meaningful on the privileged path; empty otherwise, which makes the
     * caller fall back to the normal apply — no worse than before.
     *
     * An unreadable screen degrades the same way, and that is the right direction here: not
     * knowing which apps are alive costs a relaunch, while assuming one is alive would resize
     * a task we never saw.
     */
    fun livePackages(): Set<String> {
        val observation = windowController.observeTasks()
        // "This build cannot observe" is by design on the unprivileged path and needs no
        // remark. Any other reason means a path that normally works has stopped working, and
        // the resulting empty set is a fabrication we are choosing to act on — so say so.
        if (observation is TaskObservation.Unavailable && observation.reason != "no_observer") {
            logger?.log(
                "layout", "live_packages",
                extras = mapOf("reason" to observation.reason),
                result = "unreadable:relaunching_instead_of_preserving",
            )
        }
        return observation.tasksOrEmpty.mapNotNull { it.packageName }.toSet()
    }

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
        retries: Int = LayoutSchedule.DEFAULT_RETRIES,
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

        // 4. Decide the whole thing before doing any of it.
        val plan = LayoutPlanner.plan(
            preset = preset,
            area = area,
            fullDisplay = windowController.fullDisplayArea(appContext),
            preserveLive = preserveLive,
            isLaunchable = windowController::isLaunchable,
        )
        plan.skipped.forEach { pkg ->
            warnings += "$pkg is not installed or has no launcher activity"
            logger?.log("layout", "skip_window", pkg, result = "not_launchable")
        }

        // 5. Preflight, BEFORE anything is mutated. A refusal must cost nothing.
        //
        // This used to be two checks: one here for "no app in this layout is installed", and a
        // second, identically worded one at the very *end* for the case this one could not see
        // — an anchored preset whose panel has no bounds either, since an anchored preset was
        // assumed always to place something. That second refusal ran after the stale-window
        // cleanup had been scheduled and the generation bumped, so applying a preset whose apps
        // had been uninstalled tore down the layout on screen — possibly a live navigation task
        // — and only then reported Refused. The plan knows what would reach the screen, so one
        // check now covers both, before anything moves.
        if (plan.placesNothing) {
            logger?.log(
                "layout", "apply", extras = mapOf("preset" to preset.id),
                result = "nothing_to_place",
            )
            return Result.Refused(
                "nothing to place: no app in this layout is installed with a launcher activity"
            )
        }

        // Invalidate anything still queued from an earlier apply.
        val myGeneration = generation.incrementAndGet()
        latestState = state
        applyTrigger = trigger
        handler.removeCallbacksAndMessages(RETRY_TOKEN)

        val parked = parkStaleWindows(plan.keep, plan.park.bounds, plan.park.mode, myGeneration)

        // What this apply spends before touching a tile. Everything it schedules is measured
        // from the end of it — retries and verification included, which they were not: an
        // anchored one-window layout with one stale window put the first retry at 1200 ms and
        // the launch it repairs at 1300 ms.
        val base = LayoutSchedule.preparation(parked, plan.anchored)
        if (plan.anchored) {
            postPlacement(parked * LayoutSchedule.PARK_DELAY_MS) {
                if (stillValid(myGeneration, "anchor", WitsPackages.SELF)) {
                    bringAnchorToFront(plan.panelBounds)
                }
            }
        }

        plan.steps.forEach { step -> dispatch(step, base, myGeneration) }

        lastAppliedPackages = plan.keep
        layoutOwned = true
        rateLimiter.record(ActionRateLimiter.KEY_LAYOUT)
        logger?.log(
            category = "layout", action = "apply",
            extras = mapOf(
                "preset" to preset.id,
                "windows" to plan.windowCount,
                "trigger" to trigger.name,
                "area" to area.flattenToString(),
            ),
            result = "sent", confidence = "HYP",
        )

        scheduleRetries(preset, plan, LayoutSchedule.retryPasses(retries), myGeneration, base)
        scheduleVerification(preset, plan.expected, plan.windowCount, myGeneration, verifyAttempt, base)

        // Report what was actually dispatched, not what the preset asked for: `expected` is
        // exactly the set of tiles this apply puts on screen — launchable windows, plus the
        // panel an anchored preset does not carry as a window.
        return Result.Applied(plan.expected.size, warnings)
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
        plan: LayoutPlan,
        retries: Int,
        myGeneration: Long,
        base: Long,
    ) {
        pendingRetries = retries
        if (retries <= 0) return

        // Only the windows the plan would launch: a preserved-live window is never relaunched,
        // not even on a retry — it was already there, so there is nothing to repair and a launch
        // would only risk resetting it. A window with nothing installed is not retried either.
        val repeatable = plan.steps.filter { it.phase == LayoutPlan.Phase.LAUNCH }
        if (repeatable.isEmpty()) return

        repeat(retries) { attempt ->
            val passStart = base + LayoutSchedule.retryPassStart(plan.windowCount, attempt)
            repeatable.forEach { step ->
                // What a retry does depends on the path:
                //  - Privileged: re-assert the geometry with resizeTask. Some apps (Spotify)
                //    launch at the given bounds and then grow themselves to full width; a
                //    delayed resizeTask pulls the now-visible tile back to where it belongs.
                //    `launchPackage` is a no-op when privileged, so without this the retry
                //    did nothing and the tile stayed stretched.
                //  - Unprivileged: launch only. CHANGE_WINDOW is exclusive — re-sending it
                //    would hide the other tiles and flash the layout — whereas an inclusive
                //    launch carries the bounds itself and repairs a tile that never appeared.
                postPlacement(
                    passStart + step.index * LayoutSchedule.LAUNCH_DELAY_MS, RETRY_TOKEN,
                ) {
                    if (stillValid(myGeneration, "retry_visible", step.packageName) &&
                        windowController.isLaunchable(step.packageName)
                    ) {
                        if (windowController.taskResizer != null) {
                            windowController.applyWindow(
                                WitsWindowController.WindowRequest(
                                    step.packageName, step.bounds, step.windowMode
                                )
                            )
                        } else {
                            windowController.launchPackage(
                                step.packageName, step.bounds, step.windowMode
                            )
                        }
                    }
                }
            }
            postPlacement(passStart, RETRY_TOKEN) {
                if (myGeneration == generation.get()) {
                    logger?.log(
                        "layout", "retry",
                        extras = mapOf("preset" to preset.id, "attempt" to (attempt + 1)),
                        result = "sent",
                    )
                }
            }
        }
    }

    /**
     * Sends one planned step, gated at fire time on the generation and on the vehicle still
     * being safe — not merely on what was true when the layout was requested.
     */
    private fun dispatch(step: LayoutPlan.Step, base: Long, myGeneration: Long) {
        when (step.phase) {
            LayoutPlan.Phase.GEOMETRY -> {
                // Deep link first, and immediately, so the task exists before it is positioned.
                step.launchIntentUri?.let { uri ->
                    windowController.startDeepLink(uri, step.packageName)
                }
                if (step.preserveLive) {
                    logger?.log("layout", "preserve_live", step.packageName, result = "no_relaunch")
                }
                postPlacement(base + step.offsetMs) {
                    if (stillValid(myGeneration, "geometry", step.packageName)) {
                        windowController.applyWindow(
                            WitsWindowController.WindowRequest(
                                step.packageName, step.bounds, step.windowMode
                            ),
                            preserveLive = step.preserveLive,
                            bringToFront = step.bringToFront,
                        )
                    }
                }
            }

            LayoutPlan.Phase.LAUNCH -> postPlacement(base + step.offsetMs) {
                if (stillValid(myGeneration, "make_visible", step.packageName)) {
                    windowController.launchPackage(step.packageName, step.bounds, step.windowMode)
                }
            }
        }
    }

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
        base: Long,
    ) {
        // Verification needs to read live task state; without that it could send corrections
        // but never find out whether the layout took.
        if (windowController.taskObserver == null || expected.isEmpty()) return
        if (attempt >= LayoutSchedule.VERIFY_DELAYS_MS.size) return
        // Measured from the end of the pass, like the retries, so the two never overlap.
        val delay = base + LayoutSchedule.verificationAt(windowCount, attempt)
        postPlacement(delay, RETRY_TOKEN) {
            verifyPlacement(preset, expected, myGeneration, attempt)
        }
    }

    /**
     * Compares the live task state with what the apply intended and, on a mismatch, re-asserts the
     * layout once more (bounded by [LayoutSchedule.VERIFY_DELAYS_MS]).
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

        val wrong = when (val verdict = LayoutVerification.verdict(expected, windowController.observeTasks())) {
            is LayoutVerdict.Ok -> {
                logger?.log(
                    "layout", "verify",
                    extras = mapOf("preset" to preset.id, "attempt" to attempt, "tiles" to expected.size),
                    result = "ok",
                )
                return
            }
            // The screen was never read. Re-asserting here would tear down and relaunch a
            // layout that is very likely correct — ending a live route — on no evidence at all.
            // Stop, and leave the log saying which it was.
            is LayoutVerdict.Unverifiable -> {
                logger?.log(
                    "layout", "verify",
                    extras = mapOf("preset" to preset.id, "attempt" to attempt),
                    result = "skipped:unverifiable:${verdict.reason}",
                )
                return
            }
            is LayoutVerdict.Misplaced -> verdict.tiles
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
        // any live freeform tile at all that is not part of the incoming layout. An unreadable
        // screen contributes nothing here and leaves the recorded set to do the work, which is
        // exactly what happened before tasks could be observed at all. Rapid
        // taps can leave freeform tasks we never recorded in lastAppliedPackages; without
        // this they float over the new layout (e.g. a leftover fullscreen Spotify when the
        // Cockpit is opened). SELF is never parked: the companion is the anchor/panel.
        val liveTasks = windowController.observeTasks().tasksOrEmpty
            .filter { it.windowingMode == WitsWindowMode.FREEFORM }
        val liveFreeform = liveTasks.mapNotNull { it.packageName }

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
            // Never a task we cannot name. An anonymous freeform task is one whose identity we
            // failed to resolve, not one we know to be foreign — and this branch *deletes*. The
            // cost of skipping one is a stray window; the cost of removing one is whatever it
            // was, gone.
            val toRemove = liveTasks.filter { it.packageName != null && it.packageName !in keep }
            if (toRemove.isEmpty()) return 0
            toRemove.forEachIndexed { index, task ->
                val pkg = task.packageName ?: "task:${task.taskId}"
                postPlacement(index * LayoutSchedule.PARK_DELAY_MS, RETRY_TOKEN) {
                    if (stillValid(myGeneration, "remove_stale", pkg)) remover.remove(task.taskId)
                }
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

        // Everything left to clear away: what we last placed, plus any live freeform tile that
        // is not in the incoming layout. SELF is excluded here because this path *parks* rather
        // than removes, and the companion is the anchor — the tiled branch above is the one that
        // clears our own tiles, which is why it has to run before this test and not after it.
        //
        // It did not. Anchored Maps → tiled Maps+Chrome leaves the panel as the only stale
        // window: `stale` is {SELF} before the subtraction and empty after it, so the function
        // returned 0 and the removal branch below was never reached — the exact failure its
        // comment describes, reintroduced by the guard placed above it.
        val stale = (lastAppliedPackages + liveFreeform - keep) - WitsPackages.SELF
        if (stale.isEmpty()) return 0

        // Anchored (freeform park to the floating tile) / unprivileged: move stale apps in place.
        stale.forEachIndexed { index, pkg ->
            if (!windowController.isLaunchable(pkg)) return@forEachIndexed
            postPlacement(index * LayoutSchedule.PARK_DELAY_MS, RETRY_TOKEN) {
                if (stillValid(myGeneration, "park_stale", pkg)) {
                    windowController.applyWindow(
                        WitsWindowController.WindowRequest(pkg, parkBounds, parkMode)
                    )
                }
            }
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
     * The pixel bounds the Cockpit panel window should occupy: the complement tile beside the
     * floating app when one is showing, or the whole display when the app is hidden. The panel
     * uses this to resize **its own** task ([DashboardActivity.ensurePanelBounds]) because a
     * relaunch's `setLaunchBounds` is ignored once the task exists, so it would otherwise stay
     * full-screen. Same geometry the anchored [apply] places the app with.
     */
    fun cockpitPanelBounds(split: Float, swapped: Boolean, hidden: Boolean): android.graphics.Rect {
        return LayoutGeometry.panelBounds(
            split, swapped, hidden,
            area = windowController.usableArea(appContext),
            full = windowController.fullDisplayArea(appContext),
        )
    }

    /**
     * The pixel bounds of the Cockpit's **floating-app (left) tile** — the slot the map lives in.
     * The Settings gear launches our config ([MainActivity]) into these bounds so it sits in the
     * left tile beside the panel, instead of leaving the Cockpit for a full-screen screen (which
     * fought the un-window / autostart machinery). Complement of [cockpitPanelBounds].
     */
    fun cockpitAppBounds(split: Float, swapped: Boolean): android.graphics.Rect {
        val area = windowController.usableArea(appContext)
        return LayoutGeometry.appBounds(split, swapped, area)
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
        if (state.reverseActive == true && placementsInFlight.get() > 0) {
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
        placementsInFlight.set(0)
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
        // Deliberately not counted as placement work, and deliberately not given the fire-time
        // safety guard. This *removes* our windows, so it fails toward the vendor screen — which
        // is where the reverse camera is drawn. Cancelling it partway, or refusing it, would
        // leave our tiles on top: the opposite of what the guard is for.
        // The user is taking the screen back: stop owning it, so no verification "repairs" the
        // layout they just exited (the last preset is still remembered).
        layoutOwned = false
        val myGeneration = generation.get()

        val bulkRemover = windowController.taskRemover
        if (bulkRemover != null) {
            val removed = bulkRemover.removeAllFreeform()
            if (thenGoHome) {
                handler.postDelayed({ if (myGeneration == generation.get()) goHome() }, LayoutSchedule.ANCHOR_SETTLE_MS)
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
        val liveTiles = windowController.observeTasks().tasksOrEmpty
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
            }, index * LayoutSchedule.PARK_DELAY_MS)
        }

        if (thenGoHome) {
            handler.postDelayed({
                if (myGeneration == generation.get()) goHome()
            }, tiles.size * LayoutSchedule.PARK_DELAY_MS + LayoutSchedule.ANCHOR_SETTLE_MS)
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
     * Guarded like [apply], and for the same reason: this moves a real window and grows the
     * panel to the whole display. Its sibling [io.github.miklergm.witscompanion.ui
     * .DashboardActivity].floatApp already documented going through the engine "so the reverse
     * guard, the rate limiter and the two-phase ordering all still apply" — and the *other half
     * of the same toggle* went straight past all three. Tapping the lit rail tile while the
     * reverse camera was up grew the panel over it.
     *
     * The check is a preflight: it refuses before [cancelPending], because a refusal that has
     * already cancelled in-flight work is not a refusal. No rate limit, matching [apply]'s
     * reasoning that a deliberate user tap must not be throttled.
     *
     * @param floatingPackage the app that was floating, to un-window; null skips that step.
     * @param state the vehicle state to check against, from the caller's snapshot.
     */
    fun hideFloatingApp(floatingPackage: String?, state: CarState): Result {
        when (val verdict = reverseGuard.check(state, Trigger.USER)) {
            is GuardVerdict.Blocked -> {
                logger?.log(
                    "layout", "hide_floating", floatingPackage ?: "none",
                    result = "blocked:${verdict.reason}",
                )
                return Result.Refused(verdict.reason)
            }
            GuardVerdict.Allowed -> Unit
        }

        cancelPending()
        // The fire-time re-check reads these, so a hide is judged as the user action it is.
        latestState = state
        applyTrigger = Trigger.USER
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
        //
        //    Guarded at fire time, not only at the preflight. This raise lands 250 ms later, and
        //    the reverse camera can come up inside that window — a generation check alone would
        //    then paint the panel over it. Every other delayed send in this class goes through
        //    stillValid for the same reason.
        postPlacement(LayoutSchedule.PARK_DELAY_MS) {
            if (stillValid(myGeneration, "hide_anchor", WitsPackages.SELF)) {
                bringAnchorToFront(full)
            }
        }
        lastAppliedPackages = setOf(WitsPackages.SELF)
        logger?.log(
            "layout", "hide_floating",
            floatingPackage ?: "none", result = "panel_full",
        )
        return Result.Applied(windows = 1, warnings = emptyList())
    }

    private companion object {
        const val TAG = "LayoutEngine"

        /** Token for every delayed send that a cancel has to be able to sweep. */
        val RETRY_TOKEN = Any()
    }
}
