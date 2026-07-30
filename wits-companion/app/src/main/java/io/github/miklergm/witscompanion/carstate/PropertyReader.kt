package io.github.miklergm.witscompanion.carstate

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads Android system properties from an ordinary (non-system) app.
 *
 * Three strategies, tried in order of cost:
 *
 *  1. [Strategy.REFLECTION] — `android.os.SystemProperties.get(String)` via reflection.
 *     Cheap and synchronous. May be blocked by hidden-API restrictions, though this
 *     firmware is userdebug with permissive SELinux, which usually relaxes them. [HYP]
 *
 *  2. [Strategy.GETPROP_BULK] — one `getprop` subprocess that dumps everything, parsed
 *     into a map. Used as a *bulk* fallback; never per-property in a loop.
 *
 *  3. [Strategy.UNAVAILABLE] — nothing worked.
 *
 * A JNI `__system_property_get()` wrapper is the third documented option
 * (docs/known-unknowns.md §5). It is intentionally NOT implemented in the MVP: it
 * would add an NDK toolchain requirement for a gain we cannot yet justify, since
 * strategy 1 is expected to work here. If runtime testing shows reflection is
 * blocked *and* `getprop` is too slow, add it then.
 *
 * Thread-safety: [get] may be called from any thread. [refreshBulk] performs the
 * subprocess call and must not be called from the main thread.
 */
class PropertyReader {

    enum class Strategy { REFLECTION, GETPROP_BULK, UNAVAILABLE }

    @Volatile
    var activeStrategy: Strategy = Strategy.UNAVAILABLE
        private set

    /** Diagnostic: what happened on the last probe. Shown on the Debug screen. */
    @Volatile
    var diagnostics: String = "not probed yet"
        private set

    private var systemPropertiesGet: java.lang.reflect.Method? = null
    private val bulkCache = HashMap<String, String>()

    init {
        probe()
    }

    private fun probe() {
        // Strategy 1: reflection
        try {
            @Suppress("PrivateApi")
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java)
            val probeValue = m.invoke(null, "ro.build.version.sdk") as? String
            if (!probeValue.isNullOrEmpty()) {
                systemPropertiesGet = m
                activeStrategy = Strategy.REFLECTION
                diagnostics = "reflection OK (ro.build.version.sdk=$probeValue)"
                return
            }
            diagnostics = "reflection returned empty for ro.build.version.sdk"
        } catch (t: Throwable) {
            diagnostics = "reflection failed: ${t.javaClass.simpleName}: ${t.message}"
        }

        // Strategy 2: bulk getprop
        try {
            val n = refreshBulkInternal()
            if (n > 0) {
                activeStrategy = Strategy.GETPROP_BULK
                diagnostics += " | getprop bulk OK ($n properties)"
                return
            }
            diagnostics += " | getprop bulk returned nothing"
        } catch (t: Throwable) {
            diagnostics += " | getprop failed: ${t.javaClass.simpleName}: ${t.message}"
        }

        activeStrategy = Strategy.UNAVAILABLE
    }

    /**
     * Reads a single property.
     *
     * @return the raw string, or null when the property is unset or unreadable.
     *         An empty string from the platform is normalised to null, because the
     *         firmware uses "unset" and "empty" interchangeably.
     */
    fun get(name: String): String? {
        val raw: String? = when (activeStrategy) {
            Strategy.REFLECTION -> try {
                systemPropertiesGet?.invoke(null, name) as? String
            } catch (t: Throwable) {
                Log.w(TAG, "reflection get($name) failed: ${t.message}")
                null
            }

            Strategy.GETPROP_BULK -> synchronized(bulkCache) { bulkCache[name] }

            Strategy.UNAVAILABLE -> null
        }
        return raw?.takeIf { it.isNotEmpty() }
    }

    /**
     * Refreshes the bulk cache with one subprocess call.
     * Only meaningful for [Strategy.GETPROP_BULK]; a no-op otherwise.
     *
     * Must be called off the main thread.
     */
    fun refreshBulk(): Int {
        if (activeStrategy != Strategy.GETPROP_BULK) return 0
        return try {
            refreshBulkInternal()
        } catch (t: Throwable) {
            Log.w(TAG, "refreshBulk failed: ${t.message}")
            0
        }
    }

    private fun refreshBulkInternal(): Int {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val parsed = HashMap<String, String>()
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    // Format: [name]: [value]
                    val m = GETPROP_LINE.matchEntire(line.trim()) ?: return@forEach
                    parsed[m.groupValues[1]] = m.groupValues[2]
                }
            }
        } finally {
            runCatching { process.waitFor() }
            runCatching { process.destroy() }
        }
        synchronized(bulkCache) {
            bulkCache.clear()
            bulkCache.putAll(parsed)
        }
        return parsed.size
    }

    /** Re-runs strategy detection. Exposed for the Debug screen. */
    fun reprobe() {
        systemPropertiesGet = null
        synchronized(bulkCache) { bulkCache.clear() }
        probe()
    }

    private companion object {
        const val TAG = "WitsPropertyReader"
        val GETPROP_LINE = Regex("""\[([^\]]*)]:\s*\[(.*)]""")
    }
}
