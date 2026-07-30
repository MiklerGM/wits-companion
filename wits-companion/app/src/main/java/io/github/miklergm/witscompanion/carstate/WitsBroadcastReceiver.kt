package io.github.miklergm.witscompanion.carstate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Registered at runtime with [ContextCompat.RECEIVER_NOT_EXPORTED] and never declared
 * in the manifest, so no third-party app can inject fake vehicle state into the
 * companion (docs/security.md §3.2).
 *
 * This receiver is strictly read-only: it never sends anything back.
 */
class WitsBroadcastReceiver(
    private val onUpdate: (action: String, update: BroadcastUpdate) -> Unit,
) : BroadcastReceiver() {

    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            WitsActions.CAR_STATE_ACTIONS.forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(
            context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        registered = false
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
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
     * fall back to whatever [Intent.getExtras] holds.
     */
    private fun readAny(intent: Intent, key: String): String? {
        if (!intent.hasExtra(key)) return null
        return runCatching {
            when (val v = intent.extras?.get(key)) {
                null -> null
                is Boolean -> if (v) "1" else "0"
                else -> v.toString()
            }
        }.getOrNull()
    }
}
