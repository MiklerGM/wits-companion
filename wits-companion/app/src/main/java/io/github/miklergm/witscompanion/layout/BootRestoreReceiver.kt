package io.github.miklergm.witscompanion.layout

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.miklergm.witscompanion.app.WitsCompanionApp

/**
 * Opt-in layout restore after boot.
 *
 * Deliberately cheap and delayed: `vendor/bin/wits_err_reboot.sh` reboots the unit
 * into a recovery WIPE if the system does not report readiness within 80 s
 * (docs/security.md §1.7), so nothing here may block or busy-wait.
 *
 * exported=false. BOOT_COMPLETED is a protected broadcast, so only the system
 * can deliver it.
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val app = context.applicationContext as? WitsCompanionApp ?: return
        // Two independent opt-ins ride on BOOT_COMPLETED: restoring the layout, and bringing the
        // hotspot back. onBootCompleted() gates each one itself, so this must fire when *either*
        // is enabled — gating on restoreOnBoot alone meant a user who wanted only the hotspot
        // restored never got it, because the receiver returned before reaching that call.
        val wantsLayout = app.layoutRepository.restoreOnBoot
        val wantsHotspot = app.layoutRepository.restoreHotspot
        if (!wantsLayout && !wantsHotspot) return

        app.eventLogger.log(
            "layout", "boot_received", result = "scheduled",
            extras = mapOf("layout" to wantsLayout, "hotspot" to wantsHotspot),
        )

        // Let the launcher, CenterService and the MCU settle first. This is a non-blocking
        // postDelayed (the 80 s WIPE watchdog is unaffected); the only tension is *too early* —
        // if freeform is not ready yet, the Cockpit can open full-screen without the map.
        //
        // 30 s → 12 s → 5 s. The long delays were guarding against "map-less cold boot", but that
        // turned out to be an unresolvable last-preset id, not freeform readiness (`[RUNTIME]`
        // 2026-08-17, see LayoutRepository.rebuiltAnchored). What remains of the early-fire risk is
        // now covered by the post-apply verification, which re-asserts the layout at +3 s / +8 s if
        // it did not take. BOOT_COMPLETED already lands well after the screen is touchable, so this
        // delay is pure added wait for the driver.
        //
        // goAsync() keeps the process alive across the delay. Without it onReceive() returns
        // immediately, the receiver is done, and a just-booted unit under memory pressure is
        // free to kill us before the callback ever runs. BOOT_COMPLETED is a background
        // broadcast, so the allowance is comfortably longer than the delay — but finish() is
        // called on every path regardless, because failing to release it is an ANR.
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                app.recoveryCoordinator.onBootCompleted(app.carStateRepository.state)
            } catch (t: Throwable) {
                app.eventLogger.log("layout", "boot_restore", result = "error:${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }, BOOT_DELAY_MS)
    }

    private companion object {
        const val BOOT_DELAY_MS = 5_000L
    }
}
