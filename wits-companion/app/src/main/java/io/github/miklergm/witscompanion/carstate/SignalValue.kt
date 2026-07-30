package io.github.miklergm.witscompanion.carstate

import android.os.SystemClock

/**
 * Where a value came from.
 */
enum class SignalSource { PROPERTY, BROADCAST, DERIVED, SIMULATION }

/**
 * How much we trust the value right now.
 *
 * The distinction between [UNKNOWN] and a real zero is the whole point of this type:
 * the UI must never render "0 km/h" for a signal that has simply never arrived.
 */
enum class Availability {
    /** Never received. */
    UNKNOWN,

    /** Received at least once; semantics not yet validated for this vehicle. */
    OBSERVED,

    /** Received and inside the declared valid range. */
    VALID,

    /** Last update is older than the stale timeout. */
    STALE,

    /** Known not to be populated on this device. */
    UNSUPPORTED,

    /** Received but failed parsing or range validation; [SignalValue.rawValue] kept. */
    INVALID,
    ;

    /** True when a caller may show the numeric value to the user. */
    val isPresentable: Boolean get() = this == OBSERVED || this == VALID || this == STALE
}

/**
 * A single car signal reading.
 *
 * @param value       parsed value, or null when nothing usable has been received
 * @param availability trust level, see [Availability]
 * @param source      transport it arrived on
 * @param updatedAtElapsedRealtime [SystemClock.elapsedRealtime] of the last update, or 0
 * @param rawValue    the untouched string/extra as received, kept for debugging
 */
data class SignalValue<T>(
    val value: T? = null,
    val availability: Availability = Availability.UNKNOWN,
    val source: SignalSource = SignalSource.PROPERTY,
    val updatedAtElapsedRealtime: Long = 0L,
    val rawValue: String? = null,
) {
    val isKnown: Boolean get() = availability.isPresentable && value != null

    /** Milliseconds since the last update, or null if never updated. */
    fun ageMs(now: Long = SystemClock.elapsedRealtime()): Long? =
        if (updatedAtElapsedRealtime == 0L) null else now - updatedAtElapsedRealtime

    /**
     * Returns a copy downgraded to [Availability.STALE] when the value is older than
     * [staleTimeoutMs]. Never upgrades.
     */
    fun withStaleness(staleTimeoutMs: Long, now: Long = SystemClock.elapsedRealtime()): SignalValue<T> {
        if (availability != Availability.VALID && availability != Availability.OBSERVED) return this
        val age = ageMs(now) ?: return this
        return if (age > staleTimeoutMs) copy(availability = Availability.STALE) else this
    }

    /** Human-readable value for the UI, or a placeholder that is never a fake zero. */
    fun display(unit: String? = null): String = when {
        !availability.isPresentable || value == null -> when (availability) {
            Availability.UNKNOWN -> "—"
            Availability.UNSUPPORTED -> "n/a"
            Availability.INVALID -> "invalid"
            else -> "—"
        }
        unit != null -> "$value $unit"
        else -> value.toString()
    }

    companion object {
        fun <T> unknown(source: SignalSource = SignalSource.PROPERTY): SignalValue<T> =
            SignalValue(null, Availability.UNKNOWN, source, 0L, null)

        fun <T> unsupported(source: SignalSource = SignalSource.PROPERTY): SignalValue<T> =
            SignalValue(null, Availability.UNSUPPORTED, source, 0L, null)

        /**
         * Builds a value from a raw string.
         *
         * @param validate optional range check; failing it yields [Availability.INVALID]
         */
        fun <T> of(
            raw: String?,
            source: SignalSource,
            parse: (String) -> T?,
            validate: ((T) -> Boolean)? = null,
            now: Long = SystemClock.elapsedRealtime(),
        ): SignalValue<T> {
            if (raw == null) return unknown(source)
            val parsed = try {
                parse(raw)
            } catch (_: Throwable) {
                null
            }
            if (parsed == null) {
                return SignalValue(null, Availability.INVALID, source, now, raw)
            }
            val ok = validate?.invoke(parsed) ?: true
            return SignalValue(
                value = parsed,
                availability = if (ok) Availability.VALID else Availability.INVALID,
                source = source,
                updatedAtElapsedRealtime = now,
                rawValue = raw,
            )
        }
    }
}

/** Parsing helpers shared by the repository and its tests. */
object SignalParsers {
    /** Accepts "1", "0", "true", "false"; anything else fails. */
    fun bool(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    /** Tolerates surrounding whitespace and a trailing decimal part. */
    fun int(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() }?.let {
        it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt()
    }

    fun float(raw: String): Float? = raw.trim().toFloatOrNull()

    fun string(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }
}
