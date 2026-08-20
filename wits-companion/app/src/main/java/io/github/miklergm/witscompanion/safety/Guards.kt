package io.github.miklergm.witscompanion.safety

import android.os.SystemClock
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.wits.WitsSource

/**
 * Why an action was refused.
 */
sealed interface GuardVerdict {
    data object Allowed : GuardVerdict
    data class Blocked(val reason: String) : GuardVerdict

    val isAllowed: Boolean get() = this is Allowed
    val reasonOrNull: String? get() = (this as? Blocked)?.reason
}

/**
 * How the action was initiated. Automatic actions are held to a stricter standard:
 * when reverse state is unknown they are refused ("fail closed"), while an explicit
 * user action is permitted but logged with the uncertainty.
 */
enum class Trigger { USER, AUTOMATIC }

/**
 * Refuses window changes, source switches and overlays while the reverse camera is
 * (or might be) active.
 *
 * Reverse is detected from several independent signals — see
 * docs/source-switching.md §5.
 */
class ReverseGuard(
    private val releaseDelayMs: Long = DEFAULT_RELEASE_DELAY_MS,
    private val controlMaxAgeMs: Long = DEFAULT_CONTROL_MAX_AGE_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private var lastReverseActiveAt: Long = 0L

    /** Feed every new state so the guard can track the release delay. */
    fun observe(state: CarState) {
        if (state.reverseActive == true) lastReverseActiveAt = clock()
    }

    /** Milliseconds since reverse was last seen active, or null if never. */
    fun sinceReverseMs(): Long? =
        if (lastReverseActiveAt == 0L) null else clock() - lastReverseActiveAt

    fun check(state: CarState, trigger: Trigger): GuardVerdict {
        // Control-grade evidence, not the display value: a reverse=false that stopped being
        // refreshed decays to unknown here, so an automatic action fails closed rather than
        // running on telemetry that disappeared minutes ago.
        when (state.reverseActiveForControl(clock(), controlMaxAgeMs)) {
            true -> return GuardVerdict.Blocked("reverse camera is active")

            null -> if (trigger == Trigger.AUTOMATIC) {
                return GuardVerdict.Blocked(
                    "reverse state unknown or stale; automatic actions are blocked"
                )
            }

            false -> Unit
        }

        // Source explicitly reports the reverse camera even if the boolean did not.
        if (state.source.isKnown && state.source.value == WitsSource.BACKCAR) {
            return GuardVerdict.Blocked("source is BACKCAR")
        }

        // Settle period right after reverse is released.
        val since = sinceReverseMs()
        if (trigger == Trigger.AUTOMATIC && since != null && since < releaseDelayMs) {
            return GuardVerdict.Blocked("reverse released ${since}ms ago; waiting ${releaseDelayMs}ms")
        }

        return GuardVerdict.Allowed
    }

    companion object {
        const val DEFAULT_RELEASE_DELAY_MS = 1_500L

        /**
         * How recent a `reverse=false` must be to authorise an automatic action.
         *
         * Five property polls at the default 1 s interval: long enough to ride out a couple of
         * missed reads, far short of the 30 s staleness timeout the UI uses for display.
         */
        const val DEFAULT_CONTROL_MAX_AGE_MS = 5_000L
    }
}

/**
 * Extra rules specific to changing the video source.
 */
class SourceGuard(private val reverseGuard: ReverseGuard) {

    /**
     * @param target the source id we want to switch to
     */
    fun check(state: CarState, target: Int, trigger: Trigger): GuardVerdict {
        val reverse = reverseGuard.check(state, trigger)
        if (!reverse.isAllowed) return reverse

        // Never take the screen away from the reverse camera.
        if (state.source.isKnown && state.source.value == WitsSource.BACKCAR) {
            return GuardVerdict.Blocked("refusing to switch away from the reverse camera")
        }

        // Do not fight a deliberate OEM selection automatically.
        if (trigger == Trigger.AUTOMATIC && target == WitsSource.LAUNCHER &&
            state.oemSourceActive == true
        ) {
            return GuardVerdict.Blocked("OEM is active; switching to Android must be explicit")
        }

        if (state.source.isKnown && state.source.value == target) {
            return GuardVerdict.Blocked("already on ${WitsSource.name(target)}")
        }

        return GuardVerdict.Allowed
    }
}

/**
 * Token-bucket limiter that stops both user-driven and bug-driven ping-pong.
 *
 * Limits are per action key; see docs/security.md §3.5.
 */
class ActionRateLimiter(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    data class Limit(val minIntervalMs: Long, val maxPerMinute: Int)

    private val lastAt = HashMap<String, Long>()
    private val recent = HashMap<String, ArrayDeque<Long>>()

    fun check(key: String, limit: Limit): GuardVerdict {
        val now = clock()

        lastAt[key]?.let { previous ->
            val delta = now - previous
            if (delta < limit.minIntervalMs) {
                return GuardVerdict.Blocked(
                    "rate limited: wait ${limit.minIntervalMs - delta}ms"
                )
            }
        }

        val window = recent.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() > 60_000L) window.removeFirst()
        if (window.size >= limit.maxPerMinute) {
            return GuardVerdict.Blocked("rate limited: ${limit.maxPerMinute}/min exceeded")
        }

        return GuardVerdict.Allowed
    }

    /** Call only after the action actually went ahead. */
    fun record(key: String) {
        val now = clock()
        lastAt[key] = now
        recent.getOrPut(key) { ArrayDeque() }.addLast(now)
    }

    fun reset() {
        lastAt.clear()
        recent.clear()
    }

    companion object {
        val SOURCE_SWITCH = Limit(minIntervalMs = 5_000L, maxPerMinute = 3)
        val LAYOUT_APPLY = Limit(minIntervalMs = 2_000L, maxPerMinute = 10)
        val NIGHT_MODE_WRITE = Limit(minIntervalMs = 2_000L, maxPerMinute = 20)

        const val KEY_SOURCE = "source_switch"
        const val KEY_LAYOUT = "layout_apply"
        const val KEY_NIGHT_MODE = "night_mode_write"
    }
}
