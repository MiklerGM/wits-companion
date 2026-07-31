package io.github.miklergm.witscompanion.wits

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.safety.ActionRateLimiter
import io.github.miklergm.witscompanion.safety.GuardVerdict
import io.github.miklergm.witscompanion.safety.SourceGuard
import io.github.miklergm.witscompanion.safety.Trigger

/**
 * Requests OEM ⇄ Android source changes via CenterService.
 *
 * Every call is explicit and user-initiated. There is **no** automatic switching:
 * the companion never drags the user back to Android just because it resumed
 * (docs/source-switching.md §6).
 */
class WitsSourceController(
    private val appContext: Context,
    private val sourceGuard: SourceGuard,
    private val rateLimiter: ActionRateLimiter,
    private val logger: EventLogger? = null,
    /**
     * Invoked before a source change so queued window placements are invalidated.
     * Without it, a retry scheduled a second ago would fire while the OEM screen is
     * coming up.
     */
    private val onBeforeSwitch: (() -> Unit)? = null,
) {

    sealed interface Result {
        data object Sent : Result
        data class Refused(val reason: String) : Result
        data class Error(val message: String) : Result
    }

    /**
     * Builds the `caller` extra.
     *
     * Wire format (CenterService.java:1876-1888):
     *
     *     caller = 0xA7000000 | (recoverFlag << 16) | (callerId & 0xFF)
     *
     * The 0xA7 tag is required by the receiver but is obfuscation, not
     * authorisation. We always use recoverFlag = 0 so we never take part in the
     * recover-source chain suspected of causing the "OEM bounce".
     */
    fun buildCaller(callerId: Int = WitsSource.LAUNCHER, recoverFlag: Int = 0): Int =
        TAG_WITS_APP or ((recoverFlag and 0xFF) shl 16) or (callerId and 0xFF)

    fun switchToOem(state: CarState): Result =
        request(state, WitsSource.CAN, "open_oem")

    fun switchToAndroid(state: CarState): Result =
        request(state, WitsSource.LAUNCHER, "return_to_android")

    private fun request(state: CarState, target: Int, action: String): Result {
        // 1. Safety first: reverse guard + source rules.
        when (val verdict = sourceGuard.check(state, target, Trigger.USER)) {
            is GuardVerdict.Blocked -> {
                logger?.log(
                    category = "source", action = action,
                    extras = mapOf("target" to WitsSource.name(target)),
                    result = "blocked:${verdict.reason}",
                )
                return Result.Refused(verdict.reason)
            }
            GuardVerdict.Allowed -> Unit
        }

        // 2. Rate limit.
        when (val verdict = rateLimiter.check(
            ActionRateLimiter.KEY_SOURCE, ActionRateLimiter.SOURCE_SWITCH
        )) {
            is GuardVerdict.Blocked -> {
                logger?.log("source", action, result = "rate_limited:${verdict.reason}")
                return Result.Refused(verdict.reason)
            }
            GuardVerdict.Allowed -> Unit
        }

        // Invalidate any in-flight layout work before the screen changes hands.
        onBeforeSwitch?.invoke()

        val caller = buildCaller()
        return try {
            appContext.sendBroadcast(
                Intent(WitsActions.ACTION_REQUEST_SWITCH_SOURCE).apply {
                    putExtra(WitsActions.EXTRA_STATUS, target)
                    putExtra(WitsActions.EXTRA_CALLER, caller)
                }
            )
            rateLimiter.record(ActionRateLimiter.KEY_SOURCE)
            logger?.log(
                category = "source", action = action,
                extras = mapOf(
                    "target" to WitsSource.name(target),
                    "target_id" to target,
                    "caller" to "0x%08X".format(caller),
                    "source_before" to state.sourceName,
                ),
                result = "sent", confidence = "HYP",
            )
            Result.Sent
        } catch (t: Throwable) {
            Log.w(TAG, "source switch failed", t)
            logger?.log("source", action, result = "error:${t.javaClass.simpleName}")
            Result.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "WitsSourceController"

        /** `UtilExport.TAG_WITS_APP` = -1493172224 = 0xA7000000. */
        const val TAG_WITS_APP = -1493172224
    }
}
