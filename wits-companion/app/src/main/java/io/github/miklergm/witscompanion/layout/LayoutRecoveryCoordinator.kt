package io.github.miklergm.witscompanion.layout

import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.CarStateRepository
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.safety.Trigger

/**
 * Decides *when* to re-apply the last layout.
 *
 * Design rules (docs/window-management.md §7):
 *  - every automatic trigger is opt-in and OFF by default,
 *  - nothing is re-applied while reverse is active or unknown,
 *  - the companion never switches the source; it only re-lays windows,
 *  - transitions are edge-triggered and debounced, so a steady state cannot
 *    produce repeated applications.
 */
class LayoutRecoveryCoordinator(
    private val repository: LayoutRepository,
    private val engine: LayoutEngine,
    private val reverseGuard: ReverseGuard,
    private val logger: EventLogger? = null,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : CarStateRepository.Observer {

    private var lastAcc: Boolean? = null
    private var lastAndroidSource: Boolean? = null
    private var lastReverse: Boolean? = null
    private var lastApplyAt: Long = 0L

    override fun onCarState(state: CarState) {
        reverseGuard.observe(state)
        // Let the engine re-check safety at fire time and abort in-flight sequences.
        engine.onCarState(state)

        // Leaving Android invalidates any queued window placement: the user is now
        // looking at the OEM screen (or the reverse camera) and must not have windows
        // shuffled underneath.
        val androidNow = state.androidSourceActive
        if (lastAndroidSource == true && androidNow == false) {
            logger?.log("layout", "cancel_pending", result = "left_android_source")
            engine.cancelPending()
        }

        val acc = state.acc.takeIf { it.isKnown }?.value
        val android = state.androidSourceActive
        val reverse = state.reverseActive

        // ACC OFF -> ON
        if (repository.restoreOnAcc && lastAcc == false && acc == true) {
            attempt("acc_on", state)
        }

        // Source became Android
        if (repository.restoreOnAndroidSource && lastAndroidSource != true && android == true) {
            attempt("android_source", state)
        }

        // Reverse released
        if (repository.restoreAfterReverse && lastReverse == true && reverse == false) {
            attempt("reverse_ended", state)
        }

        lastAcc = acc
        lastAndroidSource = android
        lastReverse = reverse
    }

    /** Called from MainActivity.onResume when the preference is enabled. */
    fun onActivityResumed(state: CarState) {
        if (!repository.restoreOnResume) return
        attempt("activity_resume", state)
    }

    /** Called from the boot receiver, after a deliberate delay. */
    fun onBootCompleted(state: CarState) {
        if (!repository.restoreOnBoot) return
        attempt("boot_completed", state)
    }

    /** Explicit user action; bypasses the debounce but not the safety guards. */
    fun restoreNow(state: CarState): LayoutEngine.Result {
        val preset = repository.lastAppliedPreset()
            ?: return LayoutEngine.Result.Refused("no layout has been applied yet")
        lastApplyAt = nowMs()
        return engine.apply(preset, state, Trigger.USER)
    }

    private fun attempt(reason: String, state: CarState) {
        val since = nowMs() - lastApplyAt
        if (since < DEBOUNCE_MS) {
            logger?.log(
                "layout", "auto_restore_skipped",
                extras = mapOf("reason" to reason, "since_ms" to since), result = "debounced",
            )
            return
        }
        val preset = repository.lastAppliedPreset() ?: return

        val result = engine.apply(preset, state, Trigger.AUTOMATIC)
        lastApplyAt = nowMs()
        logger?.log(
            category = "layout", action = "auto_restore",
            extras = mapOf("reason" to reason, "preset" to preset.id),
            result = when (result) {
                is LayoutEngine.Result.Applied -> "applied"
                is LayoutEngine.Result.Refused -> "refused:${result.reason}"
                is LayoutEngine.Result.Invalid -> "invalid"
            },
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
