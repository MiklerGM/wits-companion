package io.github.miklergm.witscompanion.layout

import android.content.Context
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.CarStateRepository
import io.github.miklergm.witscompanion.carstate.PropertyReader
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.safety.Trigger
import io.github.miklergm.witscompanion.wits.WitsProperties

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
    private val appContext: Context,
    private val repository: LayoutRepository,
    private val engine: LayoutEngine,
    private val reverseGuard: ReverseGuard,
    private val propertyReader: PropertyReader? = null,
    private val hotspotController: io.github.miklergm.witscompanion.wits.HotspotController? = null,
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
        if (lastAcc == false && acc == true) {
            if (repository.restoreOnAcc) attempt("acc_on", state)
            else startPanelIfEnabled("acc_on", state)
            restoreHotspotIfEnabled("acc_on")
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
        if (repository.restoreOnBoot) attempt("boot_completed", state)
        else startPanelIfEnabled("boot_completed", state)
        restoreHotspotIfEnabled("boot_completed")
    }

    /**
     * Re-enables the hotspot if the user opted in and it was on before. A short stop turns
     * the hotspot off; this brings it back on the next ACC-on or boot without a trip to the
     * quick-settings shade. Only turns it on — never off — and only from a known-off state.
     */
    fun restoreHotspotIfEnabled(reason: String) {
        val hs = hotspotController ?: return
        if (!repository.restoreHotspot) return
        if (repository.hotspotDesiredOn != true) return
        if (!hs.canToggle()) return
        val state = hs.state()
        if (state == io.github.miklergm.witscompanion.wits.HotspotController.State.OFF) {
            hs.setEnabled(true) { ok ->
                logger?.log("hotspot", "auto_restore", result = if (ok) "on" else "failed",
                    extras = mapOf("reason" to reason))
            }
        }
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
        val preset = repository.lastAppliedPreset()
        if (preset == null) {
            // Nothing to restore, but the panel autostart is independent of that.
            startPanelIfEnabled(reason, state)
            return
        }

        // Route-safe: reassert repositions live apps instead of relaunching them, so an
        // active Maps route survives a deep-sleep wake untouched. A real cold boot has no
        // live tasks, so reassert degenerates to a normal apply.
        val liveCount = engine.livePackages().size
        val result = engine.reassert(preset, state)
        lastApplyAt = nowMs()
        logger?.log(
            category = "layout", action = "auto_restore",
            extras = mapOf(
                "reason" to reason,
                "preset" to preset.id,
                "memoryBoot" to (memoryBoot()?.toString() ?: "unknown"),
                "live" to liveCount,
            ),
            result = when (result) {
                is LayoutEngine.Result.Applied -> "applied"
                is LayoutEngine.Result.Refused -> "refused:${result.reason}"
                is LayoutEngine.Result.Invalid -> "invalid"
            },
        )
        startPanelIfEnabled(reason, state)
    }

    /**
     * Brings the Mode B panel to the front, if the user opted in. Launching our own
     * activity is always safe — it disturbs no foreign app — so this is gated only by the
     * toggle and by reverse (never pull the panel up over the reverse camera).
     */
    fun startPanelIfEnabled(reason: String, state: CarState) {
        if (!repository.autostartPanel) return
        if (state.reverseActive != false) {
            logger?.log("layout", "autostart_panel", result = "skipped:reverse", extras = mapOf("reason" to reason))
            return
        }
        runCatching {
            appContext.startActivity(
                android.content.Intent(
                    appContext,
                    Class.forName("io.github.miklergm.witscompanion.ui.DashboardActivity"),
                ).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
            logger?.log("layout", "autostart_panel", result = "started", extras = mapOf("reason" to reason))
        }.onFailure {
            logger?.log("layout", "autostart_panel", result = "error:${it.javaClass.simpleName}")
        }
    }

    /** 1 = woke from deep sleep (apps alive), 0 = real boot, null = unknown/raced. */
    private fun memoryBoot(): Int? =
        propertyReader?.get(WitsProperties.MEMORY_BOOT)?.trim()?.toIntOrNull()

    private companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}
