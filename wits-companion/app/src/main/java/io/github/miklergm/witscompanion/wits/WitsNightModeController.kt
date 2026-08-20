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

        /**
         * The write was attempted and permitted, but the setting did not end up holding the
         * requested value — the provider rejected it, or something wrote over it immediately.
         * Distinct from [Error] (which means the call threw) and emphatically not [Written]:
         * reporting success for a write that did not take is how a UI ends up lying.
         */
        data class NotApplied(val reason: String) : Result
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
            // putInt returns false when the provider refuses the write; ignoring it (and the
            // readback below) meant "Written" was reported for writes that never landed.
            val accepted = Settings.System.putInt(
                appContext.contentResolver, WitsSettingsKeys.WITS_NIGHT_MODE, mode.value
            )
            rateLimiter.record(ActionRateLimiter.KEY_NIGHT_MODE)
            val after = readRaw()
            val took = after?.trim() == mode.value.toString()
            logger?.log(
                category = "night_mode", action = "write",
                extras = mapOf("old" to (before ?: "unset"), "new" to (after ?: "?"), "mode" to mode.name),
                result = when {
                    !accepted -> "rejected"
                    took -> "ok"
                    else -> "readback_mismatch"
                },
                confidence = "HYP",
            )
            when {
                !accepted -> Result.NotApplied("the system rejected the write")
                took -> Result.Written
                else -> Result.NotApplied("the setting still reads ${after ?: "unset"}")
            }
        } catch (t: Throwable) {
            logger?.log("night_mode", "write", result = "error:${t.javaClass.simpleName}")
            Result.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Restores a previously recorded raw value (used by "undo").
     *
     * [UNSET] is a real, distinct backup state, not a missing one: on this firmware
     * `wits_night_mode` has no value at all until something writes it, so the honest first
     * backup is "there was nothing here". Android offers no supported way to return a
     * `Settings.System` key to absent, so that case is refused with the actual reason rather
     * than the misleading "nothing to restore" it used to produce — the value had been
     * recorded, it simply cannot be put back.
     */
    fun restoreRaw(raw: String?): Result {
        val trimmed = raw?.trim()
        if (trimmed == null || trimmed.isEmpty()) return Result.Refused("nothing to restore")
        if (trimmed == UNSET) {
            return Result.Refused(
                "the setting was unset before; Android cannot return it to unset. " +
                    "Pick a mode explicitly instead."
            )
        }
        val v = trimmed.toIntOrNull() ?: return Result.Refused("unknown previous value: $raw")
        val mode = Mode.fromValue(v) ?: return Result.Refused("unknown previous value: $raw")
        return write(mode)
    }

    companion object {
        /** Backup marker for "the key had never been written". See [restoreRaw]. */
        const val UNSET = "unset"
    }
}
