package io.github.miklergm.witscompanion.wits

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.miklergm.witscompanion.logging.EventLogger
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Reads and toggles the Wi-Fi hotspot.
 *
 * Two capabilities with different requirements:
 *  - **status** is available on any build: `WifiManager.getWifiApState()` (a `@hide`
 *    method reached by reflection) plus the `WIFI_AP_STATE_CHANGED` broadcast. Reading
 *    needs only `ACCESS_WIFI_STATE`.
 *  - **toggling** needs `TETHER_PRIVILEGED` (signature|privileged), granted on the
 *    platform-signed build. It goes through `TetheringManager.startTethering` /
 *    `stopTethering`, reflected because those are `@SystemApi`/`@hide`.
 *
 * Everything is reflective and guarded: on a build without the privilege, [canToggle] is
 * false and [setEnabled] is a no-op, while status still works. On a stock phone/emulator
 * with no hotspot at all, [state] returns [State.UNKNOWN].
 */
class HotspotController(
    private val appContext: Context,
    private val logger: EventLogger? = null,
) {

    enum class State { ON, OFF, TURNING_ON, TURNING_OFF, FAILED, UNKNOWN }

    fun interface Listener {
        fun onHotspotState(state: State)
    }

    // ------------------------------------------------------------------- status

    /** Current hotspot state, read from `WifiManager.getWifiApState()`. */
    fun state(): State = runCatching {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) ?: return State.UNKNOWN
        val code = wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as? Int
            ?: return State.UNKNOWN
        mapApState(code)
    }.getOrDefault(State.UNKNOWN)

    val isOn: Boolean get() = state() == State.ON

    /** True when status could be read at all (the API is present). */
    fun isSupported(): Boolean = runCatching {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) ?: return false
        wifi.javaClass.getMethod("getWifiApState"); true
    }.getOrDefault(false)

    private var receiver: BroadcastReceiver? = null

    /** Registers for state-change broadcasts. Idempotent; pair with [stopObserving]. */
    fun observe(listener: Listener) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val code = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)
                listener.onHotspotState(if (code >= 0) mapApState(code) else state())
            }
        }
        receiver = r
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                appContext, r, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    fun stopObserving() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    // ------------------------------------------------------------------ toggling

    /** True when the app can actually change the hotspot (privileged build). */
    fun canToggle(): Boolean =
        appContext.checkSelfPermission(PERM_TETHER_PRIVILEGED) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Turns the hotspot on or off. [onResult] is called with success/failure; a failure on
     * an unprivileged build is expected and not an error to surface loudly.
     */
    fun setEnabled(on: Boolean, onResult: (Boolean) -> Unit = {}) {
        if (!canToggle()) {
            logger?.log("hotspot", "set_enabled", result = "no_privilege", extras = mapOf("on" to on))
            onResult(false); return
        }
        val ok = if (on) startTethering(onResult) else stopTethering(onResult)
        if (!ok) onResult(false)
    }

    // Context.TETHERING_SERVICE is @SystemApi (hidden in the public SDK); the string is
    // stable ("tethering", confirmed in this firmware's Context).
    private fun tetheringManager(): Any? =
        appContext.getSystemService(TETHERING_SERVICE)

    private fun startTethering(onResult: (Boolean) -> Unit): Boolean = runCatching {
        val tm = tetheringManager() ?: return false
        val tmClass = Class.forName("android.net.TetheringManager")

        // TetheringRequest.Builder(TETHERING_WIFI).build()
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        val request = builderClass.getMethod("build").invoke(builder)

        // StartTetheringCallback is an interface -> a dynamic proxy delivers the result.
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, _ ->
            when (method.name) {
                "onTetheringStarted" -> {
                    logger?.log("hotspot", "start", result = "started"); onResult(true)
                }
                "onTetheringFailed" -> {
                    logger?.log("hotspot", "start", result = "failed"); onResult(false)
                }
            }
            null
        }
        val executor = Executor { it.run() }
        tmClass.getMethod(
            "startTethering",
            Class.forName("android.net.TetheringManager\$TetheringRequest"),
            Executor::class.java, callbackClass,
        ).invoke(tm, request, executor, callback)
        true
    }.getOrElse {
        Log.w(TAG, "startTethering failed: ${it.javaClass.simpleName}")
        false
    }

    private fun stopTethering(onResult: (Boolean) -> Unit): Boolean = runCatching {
        val tm = tetheringManager() ?: return false
        Class.forName("android.net.TetheringManager")
            .getMethod("stopTethering", Int::class.javaPrimitiveType)
            .invoke(tm, TETHERING_WIFI)
        logger?.log("hotspot", "stop", result = "requested"); onResult(true)
        true
    }.getOrElse {
        Log.w(TAG, "stopTethering failed: ${it.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "HotspotController"

        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val TETHERING_SERVICE = "tethering"
        const val PERM_TETHER_PRIVILEGED = "android.permission.TETHER_PRIVILEGED"
        const val TETHERING_WIFI = 0

        // AOSP WifiManager.WIFI_AP_STATE_* values.
        private const val AP_DISABLING = 10
        private const val AP_DISABLED = 11
        private const val AP_ENABLING = 12
        private const val AP_ENABLED = 13
        private const val AP_FAILED = 14

        /** Pure mapping of the AOSP AP-state code to our enum, for testing. */
        fun mapApState(code: Int): State = when (code) {
            AP_ENABLED -> State.ON
            AP_DISABLED -> State.OFF
            AP_ENABLING -> State.TURNING_ON
            AP_DISABLING -> State.TURNING_OFF
            AP_FAILED -> State.FAILED
            else -> State.UNKNOWN
        }
    }
}
