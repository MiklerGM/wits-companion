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
                { windowController.applyWindow(
                    WitsWindowController.WindowRequest(window.packageName, pixels, window.windowMode)
                ) },
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

        scheduleRetries(preset, area, ordered, retries.coerceIn(0, MAX_RETRIES))

        return Result.Applied(ordered.size, warnings)
    }

    /**
     * Bounded re-application. Some window operations need a second pass because the
     * task may still have been starting. Never loops.
     */
    private fun scheduleRetries(
        preset: LayoutPreset,
        area: android.graphics.Rect,
        ordered: List<LayoutWindow>,
        retries: Int,
    ) {
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        pendingRetries = retries
        RETRY_DELAYS_MS.take(retries).forEachIndexed { attempt, delay ->
            handler.postDelayed({
                ordered.forEach { window ->
                    if (windowController.isLaunchable(window.packageName)) {
                        windowController.applyWindow(
                            WitsWindowController.WindowRequest(
                                window.packageName, window.bounds.toPixels(area), window.windowMode
                            )
                        )
                    }
                }
                logger?.log(
                    "layout", "retry",
                    extras = mapOf("preset" to preset.id, "attempt" to (attempt + 1)),
                    result = "sent",
                )
            }, RETRY_TOKEN, delay)
        }
    }

    /** Cancels any scheduled retries (e.g. when reverse engages mid-sequence). */
    fun cancelPending() {
        handler.removeCallbacksAndMessages(RETRY_TOKEN)
        pendingRetries = 0
    }

    companion object {
        const val INTER_WINDOW_DELAY_MS = 350L
        const val DEFAULT_RETRIES = 2
        const val MAX_RETRIES = 2

        /** docs/window-management.md §7: ~500 ms then ~1300 ms. */
        val RETRY_DELAYS_MS = listOf(500L, 1_300L)

        private val RETRY_TOKEN = Any()
    }
}
