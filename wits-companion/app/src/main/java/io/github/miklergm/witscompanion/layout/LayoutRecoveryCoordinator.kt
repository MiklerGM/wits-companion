package io.github.miklergm.witscompanion.layout

import android.content.Context
import android.util.Log
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

        // ACC OFF -> ON (ignition). The single "autostart on power-up" switch is backed by
        // restoreOnAcc (+ restoreOnBoot); attempt() itself brings the Cockpit up, so there is no
        // longer a separate panel-autostart to fire here.
        if (lastAcc == false && acc == true) {
            if (repository.restoreOnAcc) attempt("acc_on", state)
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

    /**
     * Called from MainActivity.onResume. Returns true if it actually brought a layout / the Cockpit
     * up, so a standalone MainActivity can yield ([android.app.Activity.moveTaskToBack]) instead of
     * leaving its full-screen config peeking behind the tiles.
     */
    fun onActivityResumed(state: CarState): Boolean {
        if (!repository.restoreOnResume) return false
        return attempt("activity_resume", state)
    }

    /** Called from the boot receiver, after a deliberate delay. */
    fun onBootCompleted(state: CarState) {
        if (repository.restoreOnBoot) attempt("boot_completed", state)
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

    /**
     * Re-apply the last layout for an automatic trigger. Returns true if a layout / the Cockpit was
     * actually brought up (so a standalone MainActivity can yield to it). No longer fires a second
     * panel-autostart after the reassert — reasserting an anchored preset already brings the panel
     * up (that double-start put a full-screen config behind the tiles, `[RUNTIME]` 2026-08-11).
     */
    private fun attempt(reason: String, state: CarState): Boolean {
        // The config UI is up (e.g. the user just tapped Settings): do NOT auto-restore over it —
        // re-applying the last anchored preset would re-float the map into the config's tile.
        if (configUiVisible) {
            logger?.log("layout", "auto_restore_skipped", extras = mapOf("reason" to reason), result = "config_visible")
            return false
        }
        val since = nowMs() - lastApplyAt
        if (since < DEBOUNCE_MS) {
            logger?.log(
                "layout", "auto_restore_skipped",
                extras = mapOf("reason" to reason, "since_ms" to since), result = "debounced",
            )
            return false
        }
        val preset = repository.lastAppliedPreset()
        if (preset == null) {
            // Fresh unit, nothing to reassert: bring the Cockpit up as the soft launcher.
            Log.i(TAG, "autostart: reason=$reason -> open Cockpit (no last layout)")
            return openCockpitPanel(reason, state)
        }

        // Route-safe: reassert repositions live apps instead of relaunching them, so an
        // active Maps route survives a deep-sleep wake untouched. A real cold boot has no
        // live tasks, so reassert degenerates to a normal apply.
        val liveCount = engine.livePackages().size
        val result = engine.reassert(preset, state)
        lastApplyAt = nowMs()
        val resultStr = when (result) {
            is LayoutEngine.Result.Applied -> "applied"
            is LayoutEngine.Result.Refused -> "refused:${result.reason}"
            is LayoutEngine.Result.Invalid -> "invalid"
        }
        // Visible in logcat so "what triggered the autostart, and did it apply?" is answerable
        // without opening the in-app event log.
        Log.i(TAG, "autostart: reason=$reason preset=${preset.id} boot=${memoryBoot() ?: "?"} live=$liveCount -> $resultStr")
        logger?.log(
            category = "layout", action = "auto_restore",
            extras = mapOf(
                "reason" to reason,
                "preset" to preset.id,
                "memoryBoot" to (memoryBoot()?.toString() ?: "unknown"),
                "live" to liveCount,
            ),
            result = resultStr,
        )
        return result is LayoutEngine.Result.Applied
    }

    /**
     * True while the config ([MainActivity]) occupies the Cockpit's **left tile**. Set solely from
     * MainActivity's resume/pause, keyed on its `isCockpitTile` flag — a standalone open (from the
     * launcher) does NOT set it, so the normal "open the app → autostart Cockpit" path still fires.
     *
     * While it holds, an auto-restore ([attempt]) and [openCockpitPanel] must NOT re-apply the last
     * layout: a `reassert` would re-float the map into the left tile and replace the config the user
     * just opened, so Settings would "never open". (Historically — before the left-tile redesign —
     * Settings un-windowed the tiles and the autostart raced to re-open the panel over MainActivity;
     * `[RUNTIME]` 2026-08-08: START MainActivity → 88 ms later START DashboardActivity from our own
     * uid. The redesign removed the un-window; this guard covers the remaining reassert race.)
     */
    @Volatile
    var configUiVisible: Boolean = false

    /**
     * Brings the Cockpit panel ([DashboardActivity]) to the front — used when there is no last layout
     * to reassert (a fresh unit). Launching our own activity disturbs no foreign app, so it is gated
     * only by the config-visible guard and by reverse (never pull the panel up over the reverse
     * camera). Returns true if the launch was issued.
     */
    private fun openCockpitPanel(reason: String, state: CarState): Boolean {
        if (configUiVisible) {
            logger?.log("layout", "autostart_panel", result = "skipped:config_visible", extras = mapOf("reason" to reason))
            return false
        }
        // Control-grade, not the display value. This is an automatic action, so it must hold
        // to the same standard as a layout apply: `reverseActive` is the last *known* reading
        // and will happily report a stale — or broadcast-forged — false. Routed through the
        // guard so there is one definition of "safe enough to act", rather than a second
        // hand-rolled check that can drift from it.
        val verdict = reverseGuard.check(state, Trigger.AUTOMATIC)
        if (!verdict.isAllowed) {
            logger?.log(
                "layout", "autostart_panel",
                result = "skipped:${verdict.reasonOrNull ?: "unsafe"}",
                extras = mapOf("reason" to reason),
            )
            return false
        }
        return runCatching {
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
            true
        }.getOrElse {
            logger?.log("layout", "autostart_panel", result = "error:${it.javaClass.simpleName}")
            false
        }
    }

    /** 1 = woke from deep sleep (apps alive), 0 = real boot, null = unknown/raced. */
    private fun memoryBoot(): Int? =
        propertyReader?.get(WitsProperties.MEMORY_BOOT)?.trim()?.toIntOrNull()

    private companion object {
        const val TAG = "LayoutRecovery"
        const val DEBOUNCE_MS = 3_000L
    }
}
