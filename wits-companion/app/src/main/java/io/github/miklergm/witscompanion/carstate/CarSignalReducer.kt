package io.github.miklergm.witscompanion.carstate

import io.github.miklergm.witscompanion.wits.WitsProfile
import io.github.miklergm.witscompanion.wits.WitsProfile.SignalId
import io.github.miklergm.witscompanion.wits.WitsProperties
import io.github.miklergm.witscompanion.wits.WitsSource

/**
 * The single place where a [CarState] is computed.
 *
 * Everything about *how* a reading becomes state lives here — parsing, staleness, and the
 * rules for reconciling the two transports. [CarStateRepository] keeps only the plumbing:
 * threads, the receiver, observers, simulation. That split is the point: this class has no
 * Android runtime dependency (every entry point takes `now` rather than reading a clock), so
 * the safety rules are testable as plain JVM unit tests instead of through Robolectric and a
 * live poll loop.
 *
 * **Not thread-safe by design.** It is a reducer, not a store: the caller owns it and must
 * serialize calls. [CarStateRepository] does that under one lock, which is what stopped the
 * polling thread and the broadcast thread losing each other's updates.
 *
 * ## Per-transport evidence
 *
 * The two transports are kept **separately** rather than written into one field. A signal
 * that arrives on both used to be whichever wrote last, so a broadcast could erase what the
 * property said and vice versa. Here each transport keeps its own reading with its own
 * timestamp, and [resolve] decides what the projected [CarState] shows.
 *
 * That matters because the transports are not equally trustworthy. The vendor receiver must
 * be registered EXPORTED to hear cross-process broadcasts and the vendor defines no signature
 * permission, so any installed app can forge one; the polled property is not app-writable.
 * See docs/security.md §3.2.
 */
class CarSignalReducer(
    private val staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS,
    private val propertyTrustWindowMs: Long = DEFAULT_PROPERTY_TRUST_WINDOW_MS,
    /** Called when a broadcast was dropped rather than applied, with the reason. */
    private val onBroadcastIgnored: ((String) -> Unit)? = null,
) {

    /** One signal as reported by each transport independently. */
    private data class Evidence<T>(
        val property: SignalValue<T> = SignalValue.unknown(SignalSource.PROPERTY),
        val broadcast: SignalValue<T> = SignalValue.unknown(SignalSource.BROADCAST),
    )

    var state: CarState = CarState()
        private set

    private var acc = Evidence<Boolean>()
    private var reverse = Evidence<Boolean>()
    private var brake = Evidence<Boolean>()
    private var illumination = Evidence<Boolean>()
    private var source = Evidence<Int>()

    // --------------------------------------------------------------- reductions

    /**
     * Folds one round of property polling in.
     *
     * @param read looks a property up by name, returning null when it is absent or empty —
     *   `PropertyReader::get` in production, a map in tests.
     */
    fun reduceProperties(read: (String) -> String?, now: Long): CarState {
        acc = acc.copy(property = keepKnown(acc.property, readSignal<Boolean>(SignalId.ACC, read, now)))
        reverse = reverse.copy(
            property = keepKnown(reverse.property, readSignal<Boolean>(SignalId.REVERSE, read, now))
        )
        brake = brake.copy(property = keepKnown(brake.property, readSignal<Boolean>(SignalId.BRAKE, read, now)))
        illumination = illumination.copy(
            property = keepKnown(illumination.property, readSignal<Boolean>(SignalId.ILLUMINATION, read, now))
        )
        source = source.copy(property = keepKnown(source.property, readSignal<Int>(SignalId.SOURCE, read, now)))

        state = project(now).copy(
            batteryVoltageRaw = merge(
                state.batteryVoltageRaw, readSignal<Float>(SignalId.BATTERY_VOLTAGE, read, now), now
            ),
            speedRaw = merge(state.speedRaw, readSignal<Int>(SignalId.SPEED, read, now), now),
            rpmRaw = merge(state.rpmRaw, readSignal<Int>(SignalId.RPM, read, now), now),
            steeringAngleRaw = merge(
                state.steeringAngleRaw, readSignal<Int>(SignalId.STEERING_ANGLE, read, now), now
            ),
            topPackage = merge(state.topPackage, readSignal<String>(SignalId.TOP_PACKAGE, read, now), now),
            // This profile publishes PDC and doors as single packed strings rather than
            // per-index properties, so keep them raw and unparsed [RUNTIME].
            radarRaw = merge(state.radarRaw, readSignal<String>(SignalId.RADAR, read, now), now),
            doorsRaw = merge(state.doorsRaw, readSignal<String>(SignalId.DOORS, read, now), now),
        )
        return state
    }

    /**
     * Reads one signal from whichever of its sysprops is populated.
     *
     * Both the property name(s) and the parser come from [WitsProfile], so this path and the
     * broadcast path cannot pick different parsers for the same signal.
     *
     * A signal with a fallback name (speed: `car.speed`, then `can.speed`) prefers the first
     * that actually *parses*, not merely the first present — a populated-but-unparseable
     * primary must not mask a good fallback. If none parse, the first present raw value is
     * still returned so it surfaces as INVALID with its text kept, rather than as UNKNOWN.
     */
    private inline fun <reified T> readSignal(
        id: SignalId,
        read: (String) -> String?,
        now: Long,
    ): SignalValue<T> {
        val signal = WitsProfile.signal(id)
        val names = signal.propertyNames
        val raw = names.firstNotNullOfOrNull { name ->
            read(name)?.takeIf { signal.parse(it) != null }
        } ?: names.firstNotNullOfOrNull(read)
        return SignalValue.of(raw, SignalSource.PROPERTY, { signal.parse(it) as? T }, null, now)
    }

    /** Folds one vendor broadcast in. Unhandled actions leave the state untouched. */
    fun reduceBroadcast(update: BroadcastUpdate, now: Long): CarState {
        fun <T> sv(value: T?, raw: String?): SignalValue<T> =
            if (value == null) SignalValue(null, Availability.INVALID, SignalSource.BROADCAST, now, raw)
            else SignalValue(value, Availability.VALID, SignalSource.BROADCAST, now, raw)

        when (update) {
            is BroadcastUpdate.Acc -> acc = acc.copy(broadcast = sv(update.on, update.raw))
            is BroadcastUpdate.Brake -> brake = brake.copy(broadcast = sv(update.on, update.raw))
            is BroadcastUpdate.Illumination ->
                illumination = illumination.copy(broadcast = sv(update.on, update.raw))
            is BroadcastUpdate.Reverse ->
                reverse = reverse.copy(broadcast = sv(update.active, update.raw))
            is BroadcastUpdate.Source ->
                source = source.copy(broadcast = sv(update.mode, update.raw))
            BroadcastUpdate.Unhandled -> return state
        }
        state = project(now)
        return state
    }

    /** Static identity properties, read once at start-up. */
    fun reduceStatic(read: (String) -> String?, now: Long): CarState {
        fun str(name: String) =
            SignalValue.of(read(name), SignalSource.PROPERTY, SignalParsers::string, null, now)
        state = state.copy(
            mcuVersion = str(WitsProperties.MCU_VERSION),
            mcuCanVersion = str(WitsProperties.MCU_CAN_VERSION),
            productId = str(WitsProperties.PRODUCT_ID),
            buildDisplayId = str(WitsProperties.BUILD_DISPLAY_ID),
        )
        return state
    }

    /** Serves a simulated snapshot verbatim; evidence is not touched. */
    fun adopt(simulated: CarState): CarState {
        state = simulated
        return state
    }

    /** Drops every reading, as if the app had just started. */
    fun reset(): CarState {
        acc = Evidence(); reverse = Evidence(); brake = Evidence()
        illumination = Evidence(); source = Evidence()
        state = CarState()
        return state
    }

    // ---------------------------------------------------------------- projection

    /** Recomputes the dual-transport fields of [state] from the current evidence. */
    private fun project(now: Long): CarState = state.copy(
        acc = resolve(acc, now),
        brake = resolve(brake, now),
        illumination = resolve(illumination, now),
        // Safety signals: a broadcast may raise the alarm but not cancel one the trusted
        // transport is still raising.
        reverse = resolve(reverse, now) { it == true },
        source = resolve(source, now) { it == WitsSource.BACKCAR },
    )

    /**
     * Decides what the projected state shows for a signal both transports report.
     *
     * Ordinarily the most recent reading wins — whichever transport spoke last is the best
     * information available. [isPositive] adds the one asymmetry that safety needs: a
     * broadcast may always *raise* an alarm (worst case a hostile app blocks our own
     * automation, which fails safe), but it may not *clear* one that the polled property is
     * still asserting and confirmed within [propertyTrustWindowMs].
     *
     * Legitimate clearing is not delayed: the next poll is at most one interval away and
     * carries the same news on the transport an app cannot write.
     */
    private fun <T> resolve(
        evidence: Evidence<T>,
        now: Long,
        isPositive: ((T?) -> Boolean)? = null,
    ): SignalValue<T> {
        val property = evidence.property.withStaleness(staleTimeoutMs, now)
        val broadcast = evidence.broadcast.withStaleness(staleTimeoutMs, now)

        if (!broadcast.isKnown && !property.isKnown) {
            // Neither is usable, so prefer whichever actually carries information: an INVALID
            // reading keeps the raw text that failed to parse, which is how an unknown vendor
            // encoding gets diagnosed, whereas UNKNOWN carries nothing at all. Losing this in
            // favour of the empty value would quietly break the guarantee in
            // docs/security.md §3.2 that unparseable extras degrade to INVALID with the raw
            // text retained.
            return listOf(property, broadcast)
                .filter { it.availability != Availability.UNKNOWN }
                .maxByOrNull { it.updatedAtElapsedRealtime }
                ?: property
        }
        // A garbage reading from one transport never erases a good one from the other.
        if (!broadcast.isKnown) return property
        if (!property.isKnown) return broadcast

        if (isPositive != null &&
            !isPositive(broadcast.value) &&
            isPositive(property.value) &&
            property.isFreshFor(propertyTrustWindowMs, now)
        ) {
            onBroadcastIgnored?.invoke("would clear a fresh property-backed positive")
            return property
        }

        return if (broadcast.updatedAtElapsedRealtime >= property.updatedAtElapsedRealtime) {
            broadcast
        } else {
            property
        }
    }

    /**
     * Keeps the previous reading when the new one is UNKNOWN, so a transient read failure
     * does not erase a signal we have already seen.
     *
     * Note what is *not* preserved: the old timestamp stays, so the retained value ages
     * normally and eventually stops being control-grade. That is the whole mechanism behind
     * [CarState.reverseActiveForControl] — a signal that stops arriving decays to unknown
     * rather than remaining "safe" forever.
     */
    private fun <T> keepKnown(previous: SignalValue<T>, fresh: SignalValue<T>): SignalValue<T> =
        if (fresh.availability == Availability.UNKNOWN && previous.isKnown) previous else fresh

    private fun <T> merge(previous: SignalValue<T>, fresh: SignalValue<T>, now: Long): SignalValue<T> =
        keepKnown(previous, fresh).withStaleness(staleTimeoutMs, now)

    companion object {
        const val DEFAULT_STALE_TIMEOUT_MS = 30_000L

        /**
         * How recently the polled property must have confirmed a safety positive for a
         * broadcast to be barred from clearing it.
         */
        const val DEFAULT_PROPERTY_TRUST_WINDOW_MS = 5_000L
    }
}
