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

    // --- trusted-transport view of the safety signals ---------------------
    // The same two signals as reported by the *polled property only*, which no installed app
    // can write. [reverse] and [source] above are the display values and may have come from a
    // vendor broadcast; these are what the guards are allowed to clear an alarm on. Populated
    // by CarSignalReducer from its per-transport evidence.
    val reverseFromProperty: SignalValue<Boolean> = SignalValue.unknown(),
    val sourceFromProperty: SignalValue<Int> = SignalValue.unknown(),
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
     * In practice there is only one on this vehicle: `wits.source` was measured staying
     * at LAUNCHER for the whole of a reverse manoeuvre, so `wits.backcar` carries this
     * alone (`[RUNTIME]` 2026-08-20, docs/car-state.md). The source branch is kept — it
     * costs nothing and other profiles may populate it — but the redundancy is not real
     * here and nothing should be built assuming it is.
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
        // Simulated telemetry is never control-grade, in either direction. The simulator
        // fabricates a reverse manoeuvre once every 90 s cycle and sets `source` to BACKCAR
        // with it, so acting on it would block a real user's tap for a reverse that is not
        // happening — and, worse, a fabricated `false` would authorise automatic actions while
        // the app has no idea what the vehicle is doing. Unknown is the honest answer: it
        // fails automatic actions closed and leaves a deliberate user action to the user.
        if (simulated) return null

        // Positive evidence counts from ANY transport and never expires. A broadcast may
        // always raise the alarm — worst case a hostile app blocks our own automation, which
        // fails safe.
        val positive = reverse.takeIf { it.isKnown }?.value == true ||
            source.takeIf { it.isKnown }?.value == WitsSource.BACKCAR
        if (positive) return true

        // Negative evidence must come from the **polled property** and be recent.
        //
        // Provenance matters as much as freshness here. The vendor receiver is registered
        // EXPORTED with no sender authentication, so any installed app can forge a
        // `reverse=false` or a `source=LAUNCHER`. Accepting broadcast negatives would let an
        // attacker refill the evidence slot with fresh values of their choosing — which would
        // defeat the freshness rule entirely, since its whole purpose is to fail closed
        // exactly when real telemetry has stopped arriving. See docs/security.md §3.2.
        val freshNegativeSignal = reverseFromProperty.takeIf { it.isKnown }?.value == false &&
            reverseFromProperty.isFreshFor(maxAgeMs, now)
        val freshNegativeSource = sourceFromProperty.takeIf { it.isKnown }?.value
            ?.let { it != WitsSource.BACKCAR } == true &&
            sourceFromProperty.isFreshFor(maxAgeMs, now)
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
