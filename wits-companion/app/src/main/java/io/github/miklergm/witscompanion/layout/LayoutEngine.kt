package io.github.miklergm.witscompanion.layout

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.safety.ActionRateLimiter
import io.github.miklergm.witscompanion.safety.GuardVerdict
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.safety.Trigger
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

    fun apply(
        preset: LayoutPreset,
        state: CarState,
        trigger: Trigger,
        retries: Int = DEFAULT_RETRIES,
        preserveLive: Set<String> = emptySet(),
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

        // 3. Rate limit.
        when (val verdict = rateLimiter.check(
            ActionRateLimiter.KEY_LAYOUT, ActionRateLimiter.LAYOUT_APPLY
        )) {
            is GuardVerdict.Blocked -> {
                logger?.log("layout", "apply", result = "rate_limited:${verdict.reason}")
                return Result.Refused(verdict.reason)
            }
            GuardVerdict.Allowed -> Unit
        }

        val warnings = issues.filter { it.severity == LayoutIssue.Severity.WARNING }
            .map { it.message }
            .toMutableList()

        val area = windowController.usableArea(appContext)
        val ordered = preset.windows.sortedBy { it.focusOrder }

        // Invalidate anything still queued from an earlier apply.
        val myGeneration = generation.incrementAndGet()
        latestState = state
        handler.removeCallbacksAndMessages(RETRY_TOKEN)

        // Windows left over from the previous layout would keep floating above the new
        // one, because a freeform task always draws over fullscreen tasks and the vendor
        // hook has no "close window" verb. Park them as plain fullscreen tasks instead:
        // they drop behind whatever comes next while their process keeps running, so
        // audio playback is unaffected.
        val parked = parkStaleWindows(keep = ordered.map { it.packageName }.toSet())

        // An anchored preset needs the companion in front before the tile is placed, so
        // the tile lands on top of it rather than behind.
        var offset = 0L
        if (preset.kind == PresetKind.ANCHORED) {
            handler.postDelayed(
                { if (stillValid(myGeneration, "anchor", WitsPackages.SELF)) bringAnchorToFront() },
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
            handler.postDelayed(
                {
                    if (stillValid(myGeneration, "geometry", window.packageName)) {
                        windowController.applyWindow(
                            WitsWindowController.WindowRequest(
                                window.packageName, pixels, window.windowMode
                            ),
                            preserveLive = preserve,
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

        return Result.Applied(ordered.size, warnings)
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
                // Launches only — deliberately no geometry phase.
                //
                // CHANGE_WINDOW is exclusive: re-sending it would hide every tile the
                // initial pass has already placed, so each retry would flash the layout.
                // A launch is inclusive and carries the bounds itself through
                // ActivityOptions.setLaunchBounds, so it still repairs a tile that never
                // became visible, without disturbing the ones that did.
                handler.postDelayed({
                    if (stillValid(myGeneration, "retry_visible", window.packageName) &&
                        windowController.isLaunchable(window.packageName)
                    ) {
                        windowController.launchPackage(window.packageName, pixels, window.windowMode)
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
     * Parks freeform windows that are not part of the incoming layout.
     *
     * There is no way to close a window through the vendor hook, and killing the process
     * would stop playback. Re-issuing `CHANGE_WINDOW` with
     * [WitsWindowMode.FULLSCREEN] turns the task back into an ordinary fullscreen task,
     * which is then occluded by whatever is placed on top.
     *
     * @return how many packages were parked, so the caller can offset what follows
     */
    private fun parkStaleWindows(keep: Set<String>): Int {
        val stale = lastAppliedPackages - keep
        if (stale.isEmpty()) return 0

        val full = windowController.fullDisplayArea(appContext)
        stale.forEachIndexed { index, pkg ->
            if (!windowController.isLaunchable(pkg)) return@forEachIndexed
            handler.postDelayed({
                windowController.applyWindow(
                    WitsWindowController.WindowRequest(pkg, full, WitsWindowMode.FULLSCREEN)
                )
            }, index * PARK_DELAY_MS)
        }
        logger?.log(
            "layout", "park_stale",
            extras = mapOf("packages" to stale.joinToString(","), "count" to stale.size),
        )
        return stale.size
    }

    /**
     * Brings the companion's own window to the front as the fullscreen anchor.
     * Uses a plain activity start — no vendor hook needed for our own package.
     *
     * Targets `DashboardActivity`, not the launcher activity: the anchor is the screen
     * behind the floating map while driving, whereas the launcher activity is the tabbed
     * configuration UI.
     */
    private fun bringAnchorToFront() {
        runCatching {
            val intent = android.content.Intent(
                appContext,
                io.github.miklergm.witscompanion.ui.DashboardActivity::class.java,
            ).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            appContext.startActivity(intent)
            logger?.log("layout", "anchor_to_front", result = "sent")
        }.onFailure {
            logger?.log("layout", "anchor_to_front", result = "error:${it.javaClass.simpleName}")
        }
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
        val verdict = reverseGuard.check(latestState, Trigger.AUTOMATIC)
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
     * and no reboot.
     *
     * Every freeform tile we placed is sized back to the full display, then the home
     * launcher is brought to the front so the vendor UI covers whatever is left. Works on
     * both paths: privileged `resizeTask` fills the tile to the display, the hook re-issues
     * it as FULLSCREEN. Either way `goHome()` guarantees a clean surface even if a tile
     * refuses to move.
     *
     * Cancels pending work first, so a delayed apply cannot re-tile behind the reset.
     */
    fun resetToVendorState() {
        cancelPending()
        val myGeneration = generation.get()
        val full = windowController.fullDisplayArea(appContext)

        // Prefer the real freeform task list when privileged; fall back to what we last
        // placed. The union covers tiles left over from an earlier session.
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

        handler.postDelayed({
            if (myGeneration == generation.get()) goHome()
        }, tiles.size * PARK_DELAY_MS + ANCHOR_SETTLE_MS)

        lastAppliedPackages = emptySet()
        logger?.log("layout", "reset_to_vendor", extras = mapOf("tiles" to tiles.size))
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
         * Gaps measured from the END of the initial pass, not from apply().
         * docs/window-management.md §7.
         */
        val RETRY_DELAYS_MS = listOf(600L, 1_600L)

        private val RETRY_TOKEN = Any()
    }
}
