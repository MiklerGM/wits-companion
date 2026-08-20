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
        val mcu = str(WitsProperties.MCU_VERSION)
        val mcuCan = str(WitsProperties.MCU_CAN_VERSION)
        val product = str(WitsProperties.PRODUCT_ID)
        val build = str(WitsProperties.BUILD_DISPLAY_ID)
        mutate { current ->
            current.copy(
                mcuVersion = mcu,
                mcuCanVersion = mcuCan,
                productId = product,
                buildDisplayId = build,
            )
        }
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

        // Read every property first, then commit in one serialized step: the broadcast
        // receiver mutates the same snapshot from the main thread, and a read-copy-write
        // straddling both threads silently loses whichever update commits first.
        val acc = boolOf(WitsProperties.ACC)
        val reverse = boolOf(WitsProperties.BACKCAR)
        val brake = boolOf(WitsProperties.BRAKE)
        val illumination = boolOf(WitsProperties.ILL)
        val battery = floatOf(WitsProperties.BATTERY_VOL)
        val rpm = intOf(WitsProperties.CAR_RATE)
        val angle = intOf(WitsProperties.CAN_ANGLE)
        val source = intOf(WitsProperties.SOURCE)
        val top = strOf(WitsProperties.TOP_PACKAGE)
        val radar = strOf(WitsProperties.CAN_RADAR)
        val doors = strOf(WitsProperties.CAN_DOOR)

        mutate { current ->
            current.copy(
                acc = merge(current.acc, acc),
                reverse = merge(current.reverse, reverse),
                brake = merge(current.brake, brake),
                illumination = merge(current.illumination, illumination),
                batteryVoltageRaw = merge(current.batteryVoltageRaw, battery),
                speedRaw = merge(current.speedRaw, speed),
                rpmRaw = merge(current.rpmRaw, rpm),
                steeringAngleRaw = merge(current.steeringAngleRaw, angle),
                source = merge(current.source, source),
                topPackage = merge(current.topPackage, top),
                // This profile publishes PDC and doors as single packed strings rather than
                // per-index properties, so keep them raw and unparsed [RUNTIME].
                radarRaw = merge(current.radarRaw, radar),
                doorsRaw = merge(current.doorsRaw, doors),
            )
        }
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

        if (update !is BroadcastUpdate.Unhandled) {
            mutate { current ->
                when (update) {
                    is BroadcastUpdate.Acc -> current.copy(acc = sv(update.on, update.raw))
                    is BroadcastUpdate.Illumination ->
                        current.copy(illumination = sv(update.on, update.raw))
                    is BroadcastUpdate.Brake -> current.copy(brake = sv(update.on, update.raw))

                    // Safety signals: a broadcast may raise the alarm but not cancel one the
                    // trusted transport is still raising. See [keepTrustedPositive].
                    is BroadcastUpdate.Reverse -> current.copy(
                        reverse = keepTrustedPositive(
                            current.reverse, sv(update.active, update.raw), now,
                        ) { it == true }
                    )
                    is BroadcastUpdate.Source -> current.copy(
                        source = keepTrustedPositive(
                            current.source, sv(update.mode, update.raw), now,
                        ) { it == WitsSource.BACKCAR }
                    )

                    BroadcastUpdate.Unhandled -> current
                }
            }
        }

        logger?.log(
            category = "carstate",
            action = "broadcast",
            extras = mapOf("action" to action, "update" to update.toString()),
            source = "BROADCAST",
        )
    }

    /**
     * Resolves a broadcast reading against what the property transport currently says.
     *
     * The vendor receiver must be registered EXPORTED to hear cross-process broadcasts, and the
     * vendor defines no signature permission to gate them — so any installed app can forge one.
     * That is tolerable for signals that only inform the UI, but not for reverse: a forged
     * `reverse=false` must not be able to unblock the guards.
     *
     * The rule is asymmetric on purpose. A broadcast may always *raise* the alarm (worst case a
     * hostile app blocks our own automation, which fails safe). It may not *clear* an alarm that
     * the polled property — the transport an app cannot write — is still asserting and has
     * confirmed recently. Legitimate clearing is not delayed: the next poll is at most one
     * interval away, and it carries the same news on the trusted transport.
     */
    private fun <T> keepTrustedPositive(
        current: SignalValue<T>,
        incoming: SignalValue<T>,
        now: Long,
        isPositive: (T?) -> Boolean,
    ): SignalValue<T> {
        val clearsTrustedAlarm = !isPositive(incoming.value) &&
            current.source == SignalSource.PROPERTY &&
            isPositive(current.value) &&
            current.isFreshFor(PROPERTY_TRUST_WINDOW_MS, now)
        if (clearsTrustedAlarm) {
            logger?.log(
                "carstate", "broadcast_ignored",
                extras = mapOf("reason" to "would clear a fresh property-backed positive"),
                source = "BROADCAST",
            )
            return current
        }
        return incoming
    }

    // ------------------------------------------------------------------ publish

    /**
     * The single writer for [state].
     *
     * Property polling runs on the `wits-carstate` worker thread and broadcasts arrive on the
     * main thread; both used to read [state], copy it and write it back, so whichever committed
     * first was silently discarded — including, on a bad interleaving, a reverse=true. Every
     * mutation now goes through here under one lock, and observers are still notified off-lock.
     */
    private fun mutate(block: (CarState) -> CarState) {
        val next: CarState
        synchronized(stateLock) {
            next = block(state)
            if (next == state) return
            state = next
        }
        notifyObservers(next)
    }

    private fun publish(next: CarState) {
        synchronized(stateLock) { state = next }
        notifyObservers(next)
    }

    private fun notifyObservers(next: CarState) {
        mainHandler.post {
            observers.forEach { runCatching { it.onCarState(next) } }
        }
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val STALE_TIMEOUT_MS = 30_000L

        /**
         * How recently the polled property must have confirmed a safety positive for a
         * broadcast to be barred from clearing it. See [keepTrustedPositive].
         */
        const val PROPERTY_TRUST_WINDOW_MS = 5_000L
    }
}
