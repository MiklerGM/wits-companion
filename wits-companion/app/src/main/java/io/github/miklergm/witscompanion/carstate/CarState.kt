package io.github.miklergm.witscompanion.carstate

import io.github.miklergm.witscompanion.wits.WitsSource

/**
 * An immutable snapshot of everything we believe about the vehicle.
 *
 * Every field defaults to [SignalValue.unknown]; nothing is ever a fabricated zero.
 * Units are deliberately absent for signals whose scaling is unproven — see
 * docs/car-state.md.
 */
data class CarState(
    // --- booleans ---------------------------------------------------------
    val acc: SignalValue<Boolean> = SignalValue.unknown(),
    val reverse: SignalValue<Boolean> = SignalValue.unknown(),
    val brake: SignalValue<Boolean> = SignalValue.unknown(),
    val illumination: SignalValue<Boolean> = SignalValue.unknown(),

    // --- numeric (units UNPROVEN — display raw) ---------------------------
    val batteryVoltageRaw: SignalValue<Float> = SignalValue.unknown(),
    val speedRaw: SignalValue<Int> = SignalValue.unknown(),
    val rpmRaw: SignalValue<Int> = SignalValue.unknown(),
    val steeringAngleRaw: SignalValue<Int> = SignalValue.unknown(),

    // --- head unit --------------------------------------------------------
    val source: SignalValue<Int> = SignalValue.unknown(),
    val topPackage: SignalValue<String> = SignalValue.unknown(),

    // --- arrays -----------------------------------------------------------
    val radar: List<SignalValue<Int>> = List(8) { SignalValue.unknown() },
    val doors: List<SignalValue<Boolean>> = List(5) { SignalValue.unknown() },
    val turnLights: List<SignalValue<Boolean>> = List(3) { SignalValue.unknown() },

    // --- static -----------------------------------------------------------
    val mcuVersion: SignalValue<String> = SignalValue.unknown(),
    val mcuCanVersion: SignalValue<String> = SignalValue.unknown(),
    val productId: SignalValue<String> = SignalValue.unknown(),
    val buildDisplayId: SignalValue<String> = SignalValue.unknown(),

    /** True while the repository is serving simulated data. */
    val simulated: Boolean = false,
) {

    // --- derived ------------------------------------------------------------

    /**
     * Reverse is considered ACTIVE if any independent indicator says so.
     *
     * Returns null when nothing is known — callers must treat null as
     * "unknown" and **fail closed** for automatic actions
     * (docs/source-switching.md §5).
     */
    val reverseActive: Boolean?
        get() {
            val bySignal = reverse.takeIf { it.isKnown }?.value
            val bySource = source.takeIf { it.isKnown }?.value?.let { it == WitsSource.BACKCAR }
            return when {
                bySignal == true || bySource == true -> true
                bySignal == false && bySource == false -> false
                bySignal == false && bySource == null -> false
                bySignal == null && bySource == false -> false
                else -> null
            }
        }

    val androidSourceActive: Boolean?
        get() = source.takeIf { it.isKnown }?.value?.let { it == WitsSource.LAUNCHER }

    val oemSourceActive: Boolean?
        get() = source.takeIf { it.isKnown }?.value?.let { it == WitsSource.CAN }

    val sourceName: String
        get() = if (source.isKnown) WitsSource.name(source.value) else "—"

    val anyDoorOpen: Boolean?
        get() {
            val known = doors.filter { it.isKnown }
            if (known.isEmpty()) return null
            return known.any { it.value == true }
        }

    /** Signals that have produced at least one reading. */
    fun observedCount(): Int = allSignals().count { it.availability.isPresentable }

    fun allSignals(): List<SignalValue<*>> = buildList {
        addAll(
            listOf(
                acc, reverse, brake, illumination,
                batteryVoltageRaw, speedRaw, rpmRaw, steeringAngleRaw,
                source, topPackage,
                mcuVersion, mcuCanVersion, productId, buildDisplayId,
            )
        )
        addAll(radar); addAll(doors); addAll(turnLights)
    }
}
