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
        // Fire if either restore-on-boot or panel autostart is enabled; onBootCompleted
        // decides which of the two to actually do.
        if (!app.layoutRepository.restoreOnBoot && !app.layoutRepository.autostartPanel) return

        app.eventLogger.log("layout", "boot_received", result = "scheduled")

        // Let the launcher, CenterService and the MCU settle first.
        Handler(Looper.getMainLooper()).postDelayed({
            app.recoveryCoordinator.onBootCompleted(app.carStateRepository.state)
        }, BOOT_DELAY_MS)
    }

    private companion object {
        const val BOOT_DELAY_MS = 30_000L
    }
}
