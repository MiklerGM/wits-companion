package io.github.miklergm.witscompanion.wits

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.safety.ActionRateLimiter
import io.github.miklergm.witscompanion.safety.GuardVerdict

/**
 * Reads and writes `Settings.System/wits_night_mode`.
 *
 * Scope: this touches only `wits_night_mode`. It does not change MCU PWM dimming,
 * `wits_skin`, or navigation day/night broadcasts (docs/night-mode.md §4). Screen
 * brightness is a separate, user-driven control — see [BrightnessController].
 */
class WitsNightModeController(
    private val appContext: Context,
    private val rateLimiter: ActionRateLimiter,
    private val logger: EventLogger? = null,
) {

    enum class Mode(val value: Int, val label: String, val description: String) {
        FOLLOW_HEADLIGHTS(0, "Follow headlights", "Dark whenever the illumination line is on"),
        FOLLOW_SCHEDULE(1, "Follow Witstek schedule", "Uses the configured start/end hours"),
        FORCE_NIGHT(2, "Force night", "Always dark"),
        FORCE_DAY(3, "Force day", "Always light — fixes permanently-on headlights"),
        ;

        companion object {
            fun fromValue(v: Int?): Mode? = entries.firstOrNull { it.value == v }
        }
    }

    sealed interface Result {
        data object Written : Result
        data object PermissionRequired : Result
        data class Refused(val reason: String) : Result
        data class Error(val message: String) : Result
    }

    fun canWrite(): Boolean = Settings.System.canWrite(appContext)

    /** Raw stored value; null means the key has never been set. */
    fun readRaw(): String? = runCatching {
        Settings.System.getString(appContext.contentResolver, WitsSettingsKeys.WITS_NIGHT_MODE)
    }.getOrNull()

    fun readMode(): Mode? = Mode.fromValue(readRaw()?.trim()?.toIntOrNull())

    /** Read-only context the UI shows next to the selector. */
    fun readContext(): Map<String, String?> = mapOf(
        WitsSettingsKeys.UI_SETTINGS to readSystem(WitsSettingsKeys.UI_SETTINGS),
        WitsSettingsKeys.WITS_SKIN to readSystem(WitsSettingsKeys.WITS_SKIN),
        WitsSettingsKeys.BACKLIGHT_CONTROL_MODE to readSystem(WitsSettingsKeys.BACKLIGHT_CONTROL_MODE),
        WitsSettingsKeys.BACKLIGHT_START_HOUR to readSystem(WitsSettingsKeys.BACKLIGHT_START_HOUR),
        WitsSettingsKeys.BACKLIGHT_END_HOUR to readSystem(WitsSettingsKeys.BACKLIGHT_END_HOUR),
    )

    private fun readSystem(key: String): String? =
        runCatching { Settings.System.getString(appContext.contentResolver, key) }.getOrNull()

    /**
     * Intent for the standard grant screen. The app never tries to self-grant.
     */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.parse("package:${appContext.packageName}"))

    /**
     * Writes the mode after checking the appop and the rate limiter.
     * The previous value is logged so it can be restored.
     */
    fun write(mode: Mode): Result {
        if (!canWrite()) {
            logger?.log("night_mode", "write", result = "permission_required")
            return Result.PermissionRequired
        }

        when (val verdict = rateLimiter.check(
            ActionRateLimiter.KEY_NIGHT_MODE, ActionRateLimiter.NIGHT_MODE_WRITE
        )) {
            is GuardVerdict.Blocked -> return Result.Refused(verdict.reason)
            GuardVerdict.Allowed -> Unit
        }

        val before = readRaw()
        return try {
            Settings.System.putInt(
                appContext.contentResolver, WitsSettingsKeys.WITS_NIGHT_MODE, mode.value
            )
            rateLimiter.record(ActionRateLimiter.KEY_NIGHT_MODE)
            val after = readRaw()
            logger?.log(
                category = "night_mode", action = "write",
                extras = mapOf("old" to (before ?: "unset"), "new" to (after ?: "?"), "mode" to mode.name),
                result = if (after?.trim() == mode.value.toString()) "ok" else "readback_mismatch",
                confidence = "HYP",
            )
            Result.Written
        } catch (t: Throwable) {
            logger?.log("night_mode", "write", result = "error:${t.javaClass.simpleName}")
            Result.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Restores a previously recorded raw value (used by "undo"). */
    fun restoreRaw(raw: String?): Result {
        val v = raw?.trim()?.toIntOrNull()
            ?: return Result.Refused("nothing to restore")
        val mode = Mode.fromValue(v) ?: return Result.Refused("unknown previous value: $raw")
        return write(mode)
    }
}
