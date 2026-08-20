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

    // --- packed raw strings -----------------------------------------------
    // This BMW profile publishes PDC as "2:0:0:4:0:0:0:0" in can.radar and doors as a
    // bitmask in can.door. The decoding is unproven, so the raw text is carried and the
    // UI shows it as-is rather than inventing per-sensor values [RUNTIME]/[HYP].
    val radarRaw: SignalValue<String> = SignalValue.unknown(),
    val doorsRaw: SignalValue<String> = SignalValue.unknown(),

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

    /**
     * Reverse state for **control** decisions — the version the guards must use.
     *
     * Differs from [reverseActive] in one way that matters: negative evidence expires.
     *
     *  - **Positive** evidence (`reverse=true`, or source `BACKCAR`) counts whenever it is known
     *    at all, however old. A camera we have seen is a camera we keep respecting.
     *  - **Negative** evidence only counts while it is fresher than [maxAgeMs]. A last-known
     *    `reverse=false` that stopped updating decays to *unknown*, not to *safe*, so automatic
     *    actions fail closed instead of running on evidence that may be minutes out of date.
     *
     * [reverseActive] keeps the old semantics for display, where showing the last reading is
     * the right behaviour.
     */
    fun reverseActiveForControl(now: Long, maxAgeMs: Long): Boolean? {
        val bySignal = reverse.takeIf { it.isKnown }?.value
        val bySource = source.takeIf { it.isKnown }?.value?.let { it == WitsSource.BACKCAR }

        // Positive evidence never expires.
        if (bySignal == true || bySource == true) return true

        // Negative evidence must be recent to count.
        val freshNegativeSignal = bySignal == false && reverse.isFreshFor(maxAgeMs, now)
        val freshNegativeSource = bySource == false && source.isFreshFor(maxAgeMs, now)
        return if (freshNegativeSignal || freshNegativeSource) false else null
    }

    val androidSourceActive: Boolean?
        get() = source.takeIf { it.isKnown }?.value?.let { it == WitsSource.LAUNCHER }

    val oemSourceActive: Boolean?
        get() = source.takeIf { it.isKnown }?.value?.let { it == WitsSource.CAN }

    val sourceName: String
        get() = if (source.isKnown) WitsSource.name(source.value) else "—"

    /**
     * Not derivable yet: `can.door` is a bitmask whose layout is unproven, so claiming
     * "a door is open" would be a guess. Returns null until the mask is decoded against
     * physically opening each door.
     */
    val anyDoorOpen: Boolean? get() = null

    /** Signals that have produced at least one reading. */
    fun observedCount(): Int = allSignals().count { it.availability.isPresentable }

    fun allSignals(): List<SignalValue<*>> = buildList {
        addAll(
            listOf(
                acc, reverse, brake, illumination,
                batteryVoltageRaw, speedRaw, rpmRaw, steeringAngleRaw,
                source, topPackage, radarRaw, doorsRaw,
                mcuVersion, mcuCanVersion, productId, buildDisplayId,
            )
        )
    }
}
