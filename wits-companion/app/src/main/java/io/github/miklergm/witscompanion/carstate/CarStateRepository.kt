package io.github.miklergm.witscompanion.carstate

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.wits.WitsProperties
import io.github.miklergm.witscompanion.wits.WitsSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the [CarState] snapshot and keeps it fresh from two transports:
 *
 *  - properties, polled on a background thread at [pollIntervalMs] (throttled,
 *    never a tight loop),
 *  - vendor broadcasts, delivered by [WitsBroadcastReceiver] (registered EXPORTED,
 *    because the senders are other processes; see that class for what replaces the
 *    lost isolation).
 *
 * Observers are notified on the main thread.
 */
class CarStateRepository(
    private val appContext: Context,
    private val propertyReader: PropertyReader = PropertyReader(),
    private val logger: EventLogger? = null,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    fun interface Observer {
        fun onCarState(state: CarState)
    }

    @Volatile
    var state: CarState = CarState()
        private set

    /** When true, polling and broadcasts are ignored and simulated data is served. */
    @Volatile
    var simulationEnabled: Boolean = false
        private set

    val propertyStrategy: PropertyReader.Strategy get() = propertyReader.activeStrategy
    val propertyDiagnostics: String get() = propertyReader.diagnostics

    private val observers = CopyOnWriteArrayList<Observer>()
    private val stateLock = Any()

    /**
     * Owns every state computation. Not thread-safe on purpose — [commit] is the lock that
     * serializes it, and keeping the reducer free of synchronization is what lets the safety
     * rules be unit-tested without a live poll loop.
     */
    private val reducer = CarSignalReducer(
        onBroadcastIgnored = { reason ->
            logger?.log(
                "carstate", "broadcast_ignored",
                extras = mapOf("reason" to reason), source = "BROADCAST",
            )
        },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var receiver: WitsBroadcastReceiver? = null
    private var simulator: CarStateSimulator? = null
    private var running = false

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        running = true

        readStaticProperties()

        receiver = WitsBroadcastReceiver { action, update ->
            applyBroadcast(action, update)
        }.also { it.register(appContext) }

        val thread = HandlerThread("wits-carstate").also { it.start() }
        worker = thread
        workerHandler = Handler(thread.looper).also { h ->
            h.post(pollRunnable)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        workerHandler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        workerHandler = null
        receiver?.unregister(appContext)
        receiver = null
        simulator?.stop()
        simulator = null
    }

    fun addObserver(observer: Observer) {
        observers += observer
        mainHandler.post { observer.onCarState(state) }
    }

    fun removeObserver(observer: Observer) {
        observers -= observer
    }

    // --------------------------------------------------------------- simulation

    fun setSimulationEnabled(enabled: Boolean) {
        if (simulationEnabled == enabled) return
        simulationEnabled = enabled
        logger?.log("carstate", if (enabled) "simulation_on" else "simulation_off")
        if (enabled) {
            simulator = CarStateSimulator { simulated -> publish(simulated) }.also { it.start() }
        } else {
            simulator?.stop()
            simulator = null
            commit { reducer.reset() }
            readStaticProperties()
        }
    }

    // ------------------------------------------------------------------ polling

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (!simulationEnabled) {
                try {
                    // Only a dump that actually happened is a reading. Reducing regardless would
                    // re-stamp whatever the reader still held with the current time, which is how
                    // a single successful `reverse=false` could stay control-grade for as long as
                    // `getprop` kept failing — exactly the case the freshness rule exists for. A
                    // skipped round leaves the last reading at its own timestamp, so it ages and
                    // automatic actions fail closed.
                    when (val refresh = propertyReader.refreshBulk()) {
                        is PropertyReader.BulkRefresh.Failed -> logger?.log(
                            "carstate", "poll_skipped",
                            extras = mapOf("reason" to refresh.reason), result = "no_reading",
                        )
                        else -> pollProperties()
                    }
                } catch (t: Throwable) {
                    logger?.log("carstate", "poll_error", result = t.javaClass.simpleName)
                }
            }
            workerHandler?.postDelayed(this, pollIntervalMs)
        }
    }

    private fun readStaticProperties() {
        commit { reducer.reduceStatic(propertyReader::get, SystemClock.elapsedRealtime()) }
    }

    private fun pollProperties() {
        commit { reducer.reduceProperties(propertyReader::get, SystemClock.elapsedRealtime()) }
    }

    // --------------------------------------------------------------- broadcasts

    private fun applyBroadcast(action: String, update: BroadcastUpdate) {
        if (simulationEnabled) return
        commit { reducer.reduceBroadcast(update, SystemClock.elapsedRealtime()) }
        logger?.log(
            category = "carstate",
            action = "broadcast",
            extras = mapOf("action" to action, "update" to update.toString()),
            source = "BROADCAST",
        )
    }

    // ------------------------------------------------------------------ publish

    /**
     * The single writer for [state], and the lock that serializes [reducer].
     *
     * [CarSignalReducer] is deliberately not thread-safe — it is a reducer, not a store — and
     * this is what makes that safe. Property polling runs on the `wits-carstate` worker thread
     * while broadcasts arrive on the main thread; before the two were serialized, both read the
     * snapshot, copied it and wrote it back, so whichever committed first was silently
     * discarded. On a bad interleaving that could be a reverse=true.
     *
     * Observers are notified off-lock.
     */
    private fun commit(reduce: () -> CarState) {
        val next: CarState
        synchronized(stateLock) {
            next = reduce()
            if (next == state) return
            state = next
        }
        notifyObservers(next)
    }

    private fun publish(next: CarState) {
        synchronized(stateLock) { state = reducer.adopt(next) }
        notifyObservers(next)
    }

    private fun notifyObservers(next: CarState) {
        mainHandler.post {
            observers.forEach { runCatching { it.onCarState(next) } }
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L

        /** Kept as an alias: the reduction rules and their tuning now live on the reducer. */
        const val STALE_TIMEOUT_MS = CarSignalReducer.DEFAULT_STALE_TIMEOUT_MS
    }
}
