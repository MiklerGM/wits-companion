package io.github.miklergm.witscompanion.carstate

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.github.miklergm.witscompanion.logging.EventLogger
import io.github.miklergm.witscompanion.wits.WitsProperties
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
            publish(CarState())
            readStaticProperties()
        }
    }

    // ------------------------------------------------------------------ polling

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (!simulationEnabled) {
                try {
                    propertyReader.refreshBulk()
                    pollProperties()
                } catch (t: Throwable) {
                    logger?.log("carstate", "poll_error", result = t.javaClass.simpleName)
                }
            }
            workerHandler?.postDelayed(this, pollIntervalMs)
        }
    }

    private fun readStaticProperties() {
        val now = SystemClock.elapsedRealtime()
        fun str(name: String) = SignalValue.of(
            propertyReader.get(name), SignalSource.PROPERTY, SignalParsers::string, null, now
        )
        publish(
            state.copy(
                mcuVersion = str(WitsProperties.MCU_VERSION),
                mcuCanVersion = str(WitsProperties.MCU_CAN_VERSION),
                productId = str(WitsProperties.PRODUCT_ID),
                buildDisplayId = str(WitsProperties.BUILD_DISPLAY_ID),
            )
        )
    }

    private fun pollProperties() {
        val now = SystemClock.elapsedRealtime()

        fun boolOf(name: String) = SignalValue.of(
            propertyReader.get(name), SignalSource.PROPERTY, SignalParsers::bool, null, now
        )

        fun intOf(name: String, validate: ((Int) -> Boolean)? = null) = SignalValue.of(
            propertyReader.get(name), SignalSource.PROPERTY, SignalParsers::int, validate, now
        )

        fun floatOf(name: String) = SignalValue.of(
            propertyReader.get(name), SignalSource.PROPERTY, SignalParsers::float, null, now
        )

        fun strOf(name: String) = SignalValue.of(
            propertyReader.get(name), SignalSource.PROPERTY, SignalParsers::string, null, now
        )

        // Speed appears under two names; prefer whichever is actually populated.
        val speed = intOf(WitsProperties.CAR_SPEED).takeIf { it.isKnown }
            ?: intOf(WitsProperties.CAN_SPEED)

        val next = state.copy(
            acc = merge(state.acc, boolOf(WitsProperties.ACC)),
            reverse = merge(state.reverse, boolOf(WitsProperties.BACKCAR)),
            brake = merge(state.brake, boolOf(WitsProperties.BRAKE)),
            illumination = merge(state.illumination, boolOf(WitsProperties.ILL)),
            batteryVoltageRaw = merge(state.batteryVoltageRaw, floatOf(WitsProperties.BATTERY_VOL)),
            speedRaw = merge(state.speedRaw, speed),
            rpmRaw = merge(state.rpmRaw, intOf(WitsProperties.CAR_RATE)),
            steeringAngleRaw = merge(state.steeringAngleRaw, intOf(WitsProperties.CAN_ANGLE)),
            source = merge(state.source, intOf(WitsProperties.SOURCE)),
            topPackage = merge(state.topPackage, strOf(WitsProperties.TOP_PACKAGE)),
            // This profile publishes PDC and doors as single packed strings rather than
            // per-index properties, so keep them raw and unparsed [RUNTIME].
            radarRaw = merge(state.radarRaw, strOf(WitsProperties.CAN_RADAR)),
            doorsRaw = merge(state.doorsRaw, strOf(WitsProperties.CAN_DOOR)),
        )
        publish(next)
    }

    /**
     * Keeps the previous reading when the new one is UNKNOWN, so a transient read
     * failure does not erase a signal we have already seen. Applies staleness.
     */
    private fun <T> merge(previous: SignalValue<T>, fresh: SignalValue<T>): SignalValue<T> {
        val chosen = if (fresh.availability == Availability.UNKNOWN && previous.isKnown) {
            previous
        } else {
            fresh
        }
        return chosen.withStaleness(STALE_TIMEOUT_MS)
    }

    // --------------------------------------------------------------- broadcasts

    private fun applyBroadcast(action: String, update: BroadcastUpdate) {
        if (simulationEnabled) return
        val now = SystemClock.elapsedRealtime()

        fun <T> sv(value: T?, raw: String?): SignalValue<T> =
            if (value == null) SignalValue(null, Availability.INVALID, SignalSource.BROADCAST, now, raw)
            else SignalValue(value, Availability.VALID, SignalSource.BROADCAST, now, raw)

        val next = when (update) {
            is BroadcastUpdate.Acc -> state.copy(acc = sv(update.on, update.raw))
            is BroadcastUpdate.Illumination -> state.copy(illumination = sv(update.on, update.raw))
            is BroadcastUpdate.Reverse -> state.copy(reverse = sv(update.active, update.raw))
            is BroadcastUpdate.Source -> state.copy(source = sv(update.mode, update.raw))
            is BroadcastUpdate.Brake -> state.copy(brake = sv(update.on, update.raw))
            BroadcastUpdate.Unhandled -> null
        }

        logger?.log(
            category = "carstate",
            action = "broadcast",
            extras = mapOf("action" to action, "update" to update.toString()),
            source = "BROADCAST",
        )

        if (next != null) publish(next)
    }

    // ------------------------------------------------------------------ publish

    private fun publish(next: CarState) {
        state = next
        mainHandler.post {
            observers.forEach { runCatching { it.onCarState(next) } }
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val STALE_TIMEOUT_MS = 30_000L
    }
}
