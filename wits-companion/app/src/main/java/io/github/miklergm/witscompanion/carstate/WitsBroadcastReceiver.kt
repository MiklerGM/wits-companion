package io.github.miklergm.witscompanion.carstate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.miklergm.witscompanion.wits.WitsActions

/**
 * Parsed form of a vendor car-state broadcast.
 */
sealed interface BroadcastUpdate {
    data class Acc(val on: Boolean?, val raw: String?) : BroadcastUpdate
    data class Illumination(val on: Boolean?, val raw: String?) : BroadcastUpdate
    data class Reverse(val active: Boolean?, val raw: String?) : BroadcastUpdate
    data class Source(val mode: Int?, val raw: String?) : BroadcastUpdate
    data class Brake(val on: Boolean?, val raw: String?) : BroadcastUpdate
    data object Unhandled : BroadcastUpdate
}

/**
 * Listens to the CenterService car-state broadcasts.
 *
 * ## Why this is registered EXPORTED
 *
 * The senders are **other processes** — `com.wits.pms` (CenterService), `com.wits.misc`
 * and SystemUI. A receiver registered `RECEIVER_NOT_EXPORTED` will not be delivered
 * their broadcasts at all, so the whole car-state feed silently goes dead and the
 * dashboard is left with nothing but polled properties.
 *
 * (That was a real defect here: this receiver was `NOT_EXPORTED` while the Signal
 * Explorer's probe was `EXPORTED`, so the explorer saw vendor events and the dashboard
 * did not.)
 *
 * ## What replaces the lost isolation
 *
 * `EXPORTED` means any app on the device can send us these actions. That is acceptable
 * only because this receiver is a **pure observer**:
 *
 *  - it accepts only the exact actions in [WitsActions.CAR_STATE_ACTIONS] and drops
 *    everything else, even if the IntentFilter somehow matched;
 *  - it never executes a command, starts a component, or writes any state;
 *  - every extra is read defensively and its raw form is kept, so a malformed or
 *    hostile payload degrades to `INVALID` rather than crashing or being trusted;
 *  - the worst a spoofed broadcast can do is make the dashboard show a wrong value.
 *
 * Safety-critical decisions must therefore **not** rest on this receiver alone.
 * [ReverseGuard][io.github.miklergm.witscompanion.safety.ReverseGuard] deliberately
 * also consults the `wits.backcar` property and the source id, so a spoofed
 * "reverse released" broadcast cannot on its own unblock an automatic action.
 *
 * See docs/security.md §3.2.
 */
class WitsBroadcastReceiver(
    private val onUpdate: (action: String, update: BroadcastUpdate) -> Unit,
) : BroadcastReceiver() {

    private var registered = false

    /** Actions actually seen, for the capability self-test. */
    private val seenActions = linkedMapOf<String, Long>()

    @Volatile
    var lastEventElapsedRealtime: Long = 0L
        private set

    @Volatile
    var receivedCount: Long = 0L
        private set

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            WitsActions.CAR_STATE_ACTIONS.forEach { addAction(it) }
        }
        // Cross-process vendor broadcasts require EXPORTED; see the class doc.
        ContextCompat.registerReceiver(
            context, this, filter, ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        registered = false
    }

    /**
     * Self-test data for the Debug screen: which vendor actions have ever arrived, and
     * how long ago the last one did. Distinguishes "the car is quiet" from "our receiver
     * is not registered correctly".
     */
    fun diagnostics(): Map<String, Long?> =
        WitsActions.CAR_STATE_ACTIONS.associateWith { seenActions[it] }

    fun ageOfLastEventMs(): Long? =
        if (lastEventElapsedRealtime == 0L) null
        else SystemClock.elapsedRealtime() - lastEventElapsedRealtime

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return

        // Defence in depth: only ever act on the exact whitelist, regardless of filter.
        if (action !in WitsActions.CAR_STATE_ACTIONS) return

        val now = SystemClock.elapsedRealtime()
        seenActions[action] = now
        lastEventElapsedRealtime = now
        receivedCount++

        onUpdate(action, parse(action, intent))
    }

    private fun parse(action: String, intent: Intent): BroadcastUpdate = when (action) {
        WitsActions.ACTION_ACC_INFO -> {
            val raw = readAny(intent, WitsActions.EXTRA_STATUS)
            BroadcastUpdate.Acc(raw?.let { SignalParsers.bool(it) }, raw)
        }

        WitsActions.ACTION_ILL_INFO -> {
            val raw = readAny(intent, WitsActions.EXTRA_STATUS)
            BroadcastUpdate.Illumination(raw?.let { SignalParsers.bool(it) }, raw)
        }

        WitsActions.ACTION_REVSTATUS, WitsActions.ACTION_REAL_REVSTATUS -> {
            // com.can.* carries an Int; com.real.* carries a Boolean. Handle both.
            val raw = readAny(intent, WitsActions.EXTRA_REVSTATUS)
            BroadcastUpdate.Reverse(raw?.let { SignalParsers.bool(it) }, raw)
        }

        WitsActions.ACTION_SOURCE_INFO -> {
            val raw = readAny(intent, WitsActions.EXTRA_SOURCE_MODE)
            BroadcastUpdate.Source(raw?.let { SignalParsers.int(it) }, raw)
        }

        WitsActions.ACTION_BRAKE_INFO -> {
            val raw = readAny(intent, WitsActions.EXTRA_STATUS)
                ?: readAny(intent, "state")
            BroadcastUpdate.Brake(raw?.let { SignalParsers.bool(it) }, raw)
        }

        else -> BroadcastUpdate.Unhandled
    }

    /**
     * Reads an extra whose wire type we cannot rely on.
     *
     * The firmware uses Int for most `status` extras but Boolean for
     * `com.real.ACTION_IO_REVSTATUS` (UtilExport.java:557-558), so probe both and
     * fall back to whatever [Intent.getExtras] holds. Anything unreadable yields null,
     * which the caller turns into `INVALID` rather than a trusted value.
     */
    private fun readAny(intent: Intent, key: String): String? {
        if (!intent.hasExtra(key)) return null
        return runCatching {
            when (val v = intent.extras?.get(key)) {
                null -> null
                is Boolean -> if (v) "1" else "0"
                is Number -> v.toString()
                is String -> v.take(MAX_EXTRA_CHARS)
                else -> v.toString().take(MAX_EXTRA_CHARS)
            }
        }.getOrNull()
    }

    private companion object {
        /** A hostile sender must not be able to blow up memory through one extra. */
        const val MAX_EXTRA_CHARS = 512
    }
}
