package io.github.miklergm.witscompanion.carstate

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

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
class PropertyReader(
    /**
     * The bulk dump command. A constructor parameter only so a test can point it at a
     * process that deliberately wedges; the app always takes the default.
     */
    private val bulkCommand: List<String> = listOf("getprop"),
    /** Wall-clock ceiling on one bulk dump, the read included. See [refreshBulkInternal]. */
    private val bulkTimeoutMs: Long = DEFAULT_BULK_TIMEOUT_MS,
) {

    enum class Strategy { REFLECTION, GETPROP_BULK, UNAVAILABLE }

    /**
     * What one call to [refreshBulk] achieved.
     *
     * Typed because the caller has to be able to tell "these readings are current" from "these
     * readings are whatever was here last time", and an `Int` could not. Returning 0 for a
     * failed dump looked harmless — the cache was kept, so nothing was *lost* — but the poll
     * loop then read that kept cache and the reducer stamped it with the current time. One
     * successful `reverse=false` stayed control-fresh for as long as `getprop` kept failing,
     * which is precisely the state the freshness rule exists to fail closed on.
     */
    sealed interface BulkRefresh {
        /** The active strategy reads in-process; there is nothing to refresh. */
        data object NotNeeded : BulkRefresh

        data class Refreshed(val count: Int) : BulkRefresh

        /** No dump was obtained. Nothing read now may be treated as a current reading. */
        data class Failed(val reason: String) : BulkRefresh

        /** True when a property read may be taken as a reading of *now*. */
        val current: Boolean get() = this !is Failed
    }

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
     * Only meaningful for [Strategy.GETPROP_BULK]; [BulkRefresh.NotNeeded] otherwise.
     *
     * On failure the cache is **emptied**, not kept. A kept cache is indistinguishable from a
     * fresh read to every caller of [get], and the poll loop turns a read into a timestamped
     * reading — so keeping it is how a stale `reverse=false` stays control-grade forever. An
     * empty cache reads as "unknown", which is what the reducer and the guards are built to
     * handle.
     *
     * Must be called off the main thread.
     */
    fun refreshBulk(): BulkRefresh {
        if (activeStrategy != Strategy.GETPROP_BULK) return BulkRefresh.NotNeeded
        return try {
            BulkRefresh.Refreshed(refreshBulkInternal())
        } catch (t: Throwable) {
            Log.w(TAG, "refreshBulk failed: ${t.message}")
            synchronized(bulkCache) { bulkCache.clear() }
            BulkRefresh.Failed("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Runs one bulk dump and replaces the cache with it.
     *
     * **The read happens on a throwaway thread and the caller waits with a deadline.** That
     * shape is the whole point, and the obvious arrangement does not work: a `waitFor(timeout)`
     * placed after — or in a `finally` around — a `readLine()` loop cannot bound anything,
     * because the loop only returns once the child closes stdout. A child that wedges mid-dump
     * blocks the reader forever and the timed wait is never reached. The bound has to sit
     * beside the read, not after it.
     *
     * That matters because [probe] runs this from the constructor, and the app constructs the
     * reader on the main thread during `Application.onCreate` — on a unit whose vendor watchdog
     * reboots into a recovery WIPE if the system does not report ready within 80 s
     * (docs/security.md §1.7).
     *
     * On timeout the process is force-killed and the partial dump is **discarded**. A truncated
     * dump is not a cheap half-answer: it would replace the whole cache, so properties that
     * were being read a second ago would silently start reading as absent. Reporting nothing
     * keeps the previous cache, and [CarSignalReducer] treats an absent property as *no new
     * information* rather than as a negative reading — which is what stops a wedged subprocess
     * from being able to clear a safety signal.
     *
     * The kill unblocks the abandoned reader by closing the pipe. A child that had forked and
     * handed the write end to a grandchild would keep it open; `getprop` does not fork, and the
     * reader is a daemon thread either way, so the caller returns on schedule regardless.
     *
     * A **non-zero exit is a failure even when the output parsed**. Plausible-looking lines
     * followed by a bad exit code mean the dump was cut short somewhere, and a short dump is a
     * set of properties that silently read as absent.
     *
     * @throws TimeoutException if the dump did not complete within [bulkTimeoutMs].
     * @throws IllegalStateException if the command exited non-zero.
     */
    private fun refreshBulkInternal(): Int {
        val process = ProcessBuilder(bulkCommand).redirectErrorStream(true).start()
        val parsed = HashMap<String, String>()
        val failure = AtomicReference<Throwable?>()
        val finished = CountDownLatch(1)

        Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        // Format: [name]: [value]
                        val m = GETPROP_LINE.matchEntire(line.trim()) ?: return@forEach
                        parsed[m.groupValues[1]] = m.groupValues[2]
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                finished.countDown()
            }
        }, READER_THREAD).apply { isDaemon = true }.start()

        val completed = finished.await(bulkTimeoutMs, TimeUnit.MILLISECONDS)
        // Either way we are done with it: on success the child has already exited, and on
        // timeout this is what releases the reader we are about to walk away from.
        runCatching { process.destroyForcibly() }

        if (!completed) {
            Log.w(TAG, "$bulkCommand did not finish within ${bulkTimeoutMs}ms; discarding it")
            throw TimeoutException("bulk property dump exceeded ${bulkTimeoutMs}ms")
        }
        failure.get()?.let { throw it }

        // The reader saw EOF, so the child has closed stdout and exiting is imminent; this wait
        // is for the reaping, not for the work.
        if (!process.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("bulk property dump did not exit after closing stdout")
        }
        val exit = process.exitValue()
        if (exit != 0) throw IllegalStateException("bulk property dump exited $exit")

        // `parsed` is only read after await() returned true, which orders the reader's writes
        // before this thread — no lock needed on it, and none taken inside the loop.
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
        const val READER_THREAD = "wits-getprop"

        /**
         * Ceiling on one bulk dump.
         *
         * Only the fallback strategy pays it, and only once per poll on a unit where
         * reflection is blocked — this one reads properties in-process, so it never gets here.
         */
        const val DEFAULT_BULK_TIMEOUT_MS = 2_000L

        /** How long to wait for the child to be reaped once it has closed its output. */
        const val EXIT_GRACE_MS = 250L
        val GETPROP_LINE = Regex("""\[([^\]]*)]:\s*\[(.*)]""")
    }
}
