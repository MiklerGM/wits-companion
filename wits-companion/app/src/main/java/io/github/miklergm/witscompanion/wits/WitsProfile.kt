package io.github.miklergm.witscompanion.wits

import io.github.miklergm.witscompanion.carstate.BroadcastUpdate
import io.github.miklergm.witscompanion.carstate.SignalParsers

/**
 * One typed description of this firmware profile: what each vehicle signal is called on every
 * transport, and how to read it.
 *
 * [WitsActions] and [WitsProperties] stay the vocabulary — the raw constants, each carrying its
 * decompiler reference. This is the *semantics*: which property, which broadcast, which extra
 * and which parser all belong to the same signal.
 *
 * That pairing used to be spread across four files, so adding or correcting a signal meant four
 * edits that nothing checked against each other. Reverse alone appeared as a property name in
 * the reducer, two actions and an extra in the receiver's `when`, and an entry in the subscribe
 * list — and the property path and the broadcast path chose their parsers independently, so
 * nothing stopped them drifting apart on a signal where that would matter.
 *
 * Confidence tags follow the repo convention: `[CODE]` from decompiled firmware, `[RUNTIME]`
 * confirmed on the vehicle, `[HYP]` believed but unproven. See docs/car-state.md.
 */
object WitsProfile {

    enum class ValueType { BOOL, INT, FLOAT, STRING }

    /** Stable identity for a signal, independent of any transport's name for it. */
    enum class SignalId {
        ACC, REVERSE, BRAKE, ILLUMINATION, SOURCE,
        BATTERY_VOLTAGE, SPEED, RPM, STEERING_ANGLE, TOP_PACKAGE, RADAR, DOORS,
    }

    /**
     * @param property        sysprop carrying this signal, or null when it is broadcast-only
     * @param propertyFallback second sysprop to try when [property] is empty on this profile
     * @param actions         vendor broadcasts carrying the same signal
     * @param extras          extra names to try **in order**; the firmware is not consistent
     * @param reduced         whether it is folded into `CarState`, as opposed to observed only
     */
    data class Signal(
        val id: SignalId,
        val label: String,
        val type: ValueType,
        val property: String? = null,
        val propertyFallback: String? = null,
        val actions: List<String> = emptyList(),
        val extras: List<String> = emptyList(),
        val confidence: String = "[CODE]",
        val note: String? = null,
        val reduced: Boolean = true,
    ) {
        /** The one parser for this signal, whichever transport it arrived on. */
        fun parse(raw: String?): Any? = raw?.let {
            when (type) {
                ValueType.BOOL -> SignalParsers.bool(it)
                ValueType.INT -> SignalParsers.int(it)
                ValueType.FLOAT -> SignalParsers.float(it)
                ValueType.STRING -> SignalParsers.string(it)
            }
        }

        /** Every sysprop name this signal may appear under, most-preferred first. */
        val propertyNames: List<String> get() = listOfNotNull(property, propertyFallback)
    }

    val SIGNALS: List<Signal> = listOf(
        Signal(
            SignalId.ACC, "Ignition (ACC)", ValueType.BOOL,
            property = WitsProperties.ACC,
            actions = listOf(WitsActions.ACTION_ACC_INFO),
            extras = listOf(WitsActions.EXTRA_STATUS),
            confidence = "[RUNTIME]",
        ),
        Signal(
            SignalId.REVERSE, "Reverse camera", ValueType.BOOL,
            property = WitsProperties.BACKCAR,
            // com.can.* carries an Int; com.real.* carries a Boolean. readAny normalises both.
            actions = listOf(WitsActions.ACTION_REVSTATUS, WitsActions.ACTION_REAL_REVSTATUS),
            extras = listOf(WitsActions.EXTRA_REVSTATUS),
            confidence = "[RUNTIME]",
            note = "Safety-critical: see CarSignalReducer for the transport-trust asymmetry.",
        ),
        Signal(
            SignalId.BRAKE, "Parking brake", ValueType.BOOL,
            property = WitsProperties.BRAKE,
            actions = listOf(WitsActions.ACTION_BRAKE_INFO),
            // "state" is a second spelling seen on this profile.
            extras = listOf(WitsActions.EXTRA_STATUS, "state"),
            confidence = "[RUNTIME]",
        ),
        Signal(
            SignalId.ILLUMINATION, "Illumination (headlights)", ValueType.BOOL,
            property = WitsProperties.ILL,
            actions = listOf(WitsActions.ACTION_ILL_INFO),
            extras = listOf(WitsActions.EXTRA_STATUS),
            confidence = "[RUNTIME]",
            note = "Drives the panel backlight, not the theme — see docs/night-mode.md.",
        ),
        Signal(
            SignalId.SOURCE, "Head-unit source", ValueType.INT,
            property = WitsProperties.SOURCE,
            actions = listOf(WitsActions.ACTION_SOURCE_INFO),
            extras = listOf(WitsActions.EXTRA_SOURCE_MODE),
            confidence = "[RUNTIME]",
            note = "BACKCAR here is reverse evidence in its own right.",
        ),

        // --- property-only ---------------------------------------------------
        Signal(
            SignalId.BATTERY_VOLTAGE, "Battery voltage", ValueType.FLOAT,
            property = WitsProperties.BATTERY_VOL, confidence = "[HYP]",
            note = "Scaling unproven — displayed raw.",
        ),
        Signal(
            SignalId.SPEED, "Speed", ValueType.INT,
            property = WitsProperties.CAR_SPEED, propertyFallback = WitsProperties.CAN_SPEED,
            confidence = "[RUNTIME]",
            note = "car.speed is empty on this profile; can.speed carries it.",
        ),
        Signal(SignalId.RPM, "Engine rate", ValueType.INT, property = WitsProperties.CAR_RATE, confidence = "[HYP]"),
        Signal(
            SignalId.STEERING_ANGLE, "Steering angle", ValueType.INT,
            property = WitsProperties.CAN_ANGLE, confidence = "[RUNTIME]",
        ),
        Signal(
            SignalId.TOP_PACKAGE, "Foreground package", ValueType.STRING,
            property = WitsProperties.TOP_PACKAGE, confidence = "[RUNTIME]",
        ),
        Signal(
            SignalId.RADAR, "PDC (packed)", ValueType.STRING,
            property = WitsProperties.CAN_RADAR, confidence = "[RUNTIME]",
            note = "Packed string like \"2:0:0:4:0:0:0:0\"; decoding unproven, kept raw.",
        ),
        Signal(
            SignalId.DOORS, "Doors (bitmask)", ValueType.STRING,
            property = WitsProperties.CAN_DOOR, confidence = "[RUNTIME]",
            note = "Bitmask like \"ffffff80\"; decoding unproven, so no door state is claimed.",
        ),
    )

    /**
     * Vendor broadcasts the receiver subscribes to but does **not** fold into `CarState`.
     *
     * They are still worth hearing: the Signal Explorer records them, and the capability
     * self-test distinguishes "the car is quiet" from "our receiver is misregistered".
     */
    val OBSERVE_ONLY_ACTIONS: List<String> = listOf(
        WitsActions.ACTION_RADAR_VIEW,
        WitsActions.ACTION_KEY_CODE,
        WitsActions.ACTION_CAR_VIDEO_STATUS,
        WitsActions.ACTION_MUSIC_INFO,
        WitsActions.ACTION_RADIO_INFO,
        WitsActions.ACTION_BT_INFO,
        WitsActions.ACTION_BATTERY_VOL,
    )

    /** Everything the car-state receiver listens for. Read-only; nothing here is a command. */
    val OBSERVED_ACTIONS: List<String> =
        SIGNALS.flatMap { it.actions } + OBSERVE_ONLY_ACTIONS

    private val byAction: Map<String, Signal> =
        SIGNALS.flatMap { signal -> signal.actions.map { it to signal } }.toMap()

    fun signalFor(action: String): Signal? = byAction[action]

    fun signal(id: SignalId): Signal = SIGNALS.first { it.id == id }

    /** Every sysprop this profile reads, for diagnostics and doc-consistency checks. */
    val ALL_PROPERTIES: List<String> = SIGNALS.flatMap { it.propertyNames }.distinct()

    /**
     * Turns a raw extra into the update the reducer folds in.
     *
     * The one place that maps a signal onto [BroadcastUpdate]'s constructors — every other
     * step (which action, which extra, which parser) comes from the table above.
     */
    fun toUpdate(signal: Signal, raw: String?): BroadcastUpdate = when (signal.id) {
        SignalId.ACC -> BroadcastUpdate.Acc(signal.parse(raw) as? Boolean, raw)
        SignalId.ILLUMINATION -> BroadcastUpdate.Illumination(signal.parse(raw) as? Boolean, raw)
        SignalId.REVERSE -> BroadcastUpdate.Reverse(signal.parse(raw) as? Boolean, raw)
        SignalId.BRAKE -> BroadcastUpdate.Brake(signal.parse(raw) as? Boolean, raw)
        SignalId.SOURCE -> BroadcastUpdate.Source(signal.parse(raw) as? Int, raw)
        else -> BroadcastUpdate.Unhandled
    }
}
