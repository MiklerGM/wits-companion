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
import io.github.miklergm.witscompanion.wits.WitsWindowController
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

    /**
     * @param trigger USER for a button press, AUTOMATIC for a restore
     * @param retries how many bounded retries to schedule (0..2)
     */
    fun apply(
        preset: LayoutPreset,
        state: CarState,
        trigger: Trigger,
        retries: Int = DEFAULT_RETRIES,
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
            handler.postDelayed(
                {
                    if (stillValid(myGeneration, "initial", window.packageName)) {
                        windowController.applyWindow(
                            WitsWindowController.WindowRequest(
                                window.packageName, pixels, window.windowMode
                            )
                        )
                    }
                },
                index * INTER_WINDOW_DELAY_MS,
            )
        }

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

        scheduleRetries(preset, area, ordered, retries.coerceIn(0, MAX_RETRIES), myGeneration)

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
    ) {
        pendingRetries = retries
        if (retries <= 0 || ordered.isEmpty()) return

        // The initial pass finishes at (n-1) * INTER_WINDOW_DELAY_MS.
        val initialPassEnd = (ordered.size - 1) * INTER_WINDOW_DELAY_MS

        RETRY_DELAYS_MS.take(retries).forEachIndexed { attempt, gap ->
            val passStart = initialPassEnd + gap
            ordered.forEachIndexed { index, window ->
                val at = passStart + index * INTER_WINDOW_DELAY_MS
                handler.postDelayed({
                    if (stillValid(myGeneration, "retry", window.packageName) &&
                        windowController.isLaunchable(window.packageName)
                    ) {
                        windowController.applyWindow(
                            WitsWindowController.WindowRequest(
                                window.packageName, window.bounds.toPixels(area), window.windowMode
                            )
                        )
                    }
                }, RETRY_TOKEN, at)
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
        repeat(windowCount) { i -> out += i * INTER_WINDOW_DELAY_MS }
        val initialPassEnd = (windowCount - 1) * INTER_WINDOW_DELAY_MS
        RETRY_DELAYS_MS.take(retries.coerceIn(0, MAX_RETRIES)).forEach { gap ->
            repeat(windowCount) { i -> out += initialPassEnd + gap + i * INTER_WINDOW_DELAY_MS }
        }
        return out
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

    companion object {
        const val INTER_WINDOW_DELAY_MS = 350L
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
