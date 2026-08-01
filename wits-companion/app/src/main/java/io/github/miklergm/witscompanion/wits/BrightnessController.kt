package io.github.miklergm.witscompanion.wits

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.miklergm.witscompanion.logging.EventLogger
import kotlin.math.roundToInt

/**
 * Nudges the panel backlight through `Settings.System.SCREEN_BRIGHTNESS`.
 *
 * A **relative** control (− / + steps), not a slider: while driving, a couple of taps to
 * soften a too-bright night level or lift a too-dim day level is all that is wanted, and
 * there is no ambient-light sensor on this unit to automate it — day/night is the CAN
 * illumination (headlight) line, not a lux reading (docs/backlog.md, Brightness).
 *
 * `[UNVERIFIED on the head unit]` It is not yet confirmed whether this panel's backlight
 * follows the framework `SCREEN_BRIGHTNESS` or the vendor MCU. On the emulator it *does*
 * follow it, which is where this is validated. If the MCU overrides it on the car the value
 * will snap back on the next illumination event — that is the on-car test to run, not a bug
 * in this class.
 *
 * Two safety rules:
 *  - never set brightness to zero — the screen must not go black while moving; a floor is
 *    enforced ([MIN_RAW]);
 *  - switch to manual mode first, so an auto-brightness sensor does not immediately undo the
 *    step.
 *
 * Writing `SCREEN_BRIGHTNESS` needs the `WRITE_SETTINGS` appop — held by signature on the
 * platform build, grantable via the system screen otherwise ([permissionIntent]).
 */
class BrightnessController(
    private val appContext: Context,
    private val logger: EventLogger? = null,
) {

    sealed interface Result {
        /** New brightness as a 0..100 percentage, for the UI to show. */
        data class Written(val percent: Int) : Result
        data object PermissionRequired : Result
        data class Error(val message: String) : Result
    }

    fun canWrite(): Boolean = Settings.System.canWrite(appContext)

    /** Standard grant screen; the app does not self-grant this appop. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(Uri.parse("package:${appContext.packageName}"))

    /** Raw 0..255 value, or null when the key has never been set / is unreadable. */
    private fun readRaw(): Int? = runCatching {
        Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()

    /** Current brightness as a 0..100 percentage, or null if unreadable. */
    fun percent(): Int? = readRaw()?.let { rawToPercent(it) }

    /** True when auto-brightness is on, so the UI can note a step may be re-adjusted. */
    fun isAutomatic(): Boolean = runCatching {
        Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    fun brighten(): Result = nudge(+STEP_FRACTION)
    fun dim(): Result = nudge(-STEP_FRACTION)

    /**
     * Adds [deltaFraction] of the full range to the current level, clamped to [MIN_RAW]..
     * [MAX_RAW], after switching off auto-brightness so the step sticks.
     */
    fun nudge(deltaFraction: Float): Result {
        if (!canWrite()) {
            logger?.log("brightness", "nudge", result = "permission_required")
            return Result.PermissionRequired
        }
        val current = readRaw() ?: DEFAULT_RAW
        val next = nextRaw(current, deltaFraction)
        return try {
            // Manual mode first: an auto-brightness sensor would otherwise re-adjust the panel
            // right after the step, making the button look like it did nothing.
            runCatching {
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            Settings.System.putInt(
                appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next
            )
            val readback = readRaw()
            logger?.log(
                category = "brightness", action = "nudge",
                extras = mapOf("old" to (current), "new" to next, "delta" to deltaFraction),
                result = if (readback == next) "ok" else "readback=$readback",
                confidence = "HYP",
            )
            Result.Written(rawToPercent(next))
        } catch (t: Throwable) {
            logger?.log("brightness", "nudge", result = "error:${t.javaClass.simpleName}")
            Result.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * The step arithmetic, kept pure and internal so it is unit-testable without the Android
     * settings provider: add [deltaFraction] of the full range and clamp so the panel never
     * goes black and never exceeds the maximum.
     */
    internal companion object {
        const val MAX_RAW = 255

        /** Floor (~5 %): dim is useful, black is not — never let the panel go dark in motion. */
        const val MIN_RAW = 12

        /** Where to start a step from when the current value cannot be read. */
        const val DEFAULT_RAW = 128

        /** Step size as a fraction of the full range (~20 %). */
        const val STEP_FRACTION = 0.2f

        fun nextRaw(currentRaw: Int, deltaFraction: Float): Int =
            (currentRaw + (deltaFraction * MAX_RAW).roundToInt()).coerceIn(MIN_RAW, MAX_RAW)

        fun rawToPercent(raw: Int): Int =
            ((raw.coerceIn(0, MAX_RAW) * 100f) / MAX_RAW).roundToInt()
    }
}
