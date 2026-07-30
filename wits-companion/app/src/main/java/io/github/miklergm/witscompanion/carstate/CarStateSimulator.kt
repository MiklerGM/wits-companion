package io.github.miklergm.witscompanion.carstate

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.miklergm.witscompanion.wits.WitsSource
import org.json.JSONObject
import java.io.File

/**
 * Generates synthetic [CarState] so the dashboard can be developed on an ordinary
 * phone, tablet or emulator.
 *
 * Guarantees:
 *  - sends no broadcast to CenterService,
 *  - never switches the source,
 *  - never touches the MCU,
 *  - every produced signal carries [SignalSource.SIMULATION] so the UI can mark it.
 *
 * It can also replay a JSONL session recorded by `tools/capture-car-state.sh`.
 */
class CarStateSimulator(
    private val onState: (CarState) -> Unit,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var tick = 0
    private var replay: List<ReplayEvent>? = null
    private var replayIndex = 0

    data class ReplayEvent(val elapsedS: Long, val property: String, val value: String)

    fun start() {
        if (thread != null) return
        val t = HandlerThread("wits-simulator").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(loop) }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        tick = 0
        replayIndex = 0
    }

    /**
     * Loads a recorded session. Expected format: one JSON object per line with
     * `elapsed_s`, `property`, `new` — as written by tools/capture-car-state.sh.
     */
    fun loadReplay(file: File): Int {
        val events = mutableListOf<ReplayEvent>()
        runCatching {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = JSONObject(line)
                if (o.optString("category") != "property") return@forEachLine
                events += ReplayEvent(
                    elapsedS = o.optLong("elapsed_s"),
                    property = o.optString("property"),
                    value = o.optString("new"),
                )
            }
        }
        replay = events.takeIf { it.isNotEmpty() }
        replayIndex = 0
        return events.size
    }

    private val loop = object : Runnable {
        override fun run() {
            tick++
            onState(if (replay != null) nextReplayState() else nextSyntheticState())
            handler?.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------- synthetic

    private var syntheticState = CarState(simulated = true)

    private fun nextSyntheticState(): CarState {
        val now = SystemClock.elapsedRealtime()
        fun <T> sim(v: T, raw: String): SignalValue<T> =
            SignalValue(v, Availability.VALID, SignalSource.SIMULATION, now, raw)

        // A gentle 90 s scenario: drive, indicate, brake, park, reverse.
        val phase = (tick % 90)
        val speed = when {
            phase < 10 -> 0
            phase < 40 -> ((phase - 10) * 3).coerceAtMost(90)
            phase < 60 -> 90 - (phase - 40) * 4
            else -> 0
        }.coerceAtLeast(0)

        val reversing = phase in 70..80
        val leftBlink = phase in 30..38 && (tick % 2 == 0)
        val rightBlink = phase in 55..62 && (tick % 2 == 0)

        syntheticState = syntheticState.copy(
            simulated = true,
            acc = sim(true, "1"),
            reverse = sim(reversing, if (reversing) "1" else "0"),
            brake = sim(phase in 58..66, "0"),
            illumination = sim(phase > 45, if (phase > 45) "1" else "0"),
            batteryVoltageRaw = sim(if (speed > 0) 14.2f else 12.4f, "142"),
            speedRaw = sim(speed, speed.toString()),
            rpmRaw = sim(if (speed == 0) 780 else 900 + speed * 22, "—"),
            steeringAngleRaw = sim(
                (kotlin.math.sin(tick / 6.0) * 180).toInt(), "sim"
            ),
            source = sim(
                if (reversing) WitsSource.BACKCAR else WitsSource.LAUNCHER,
                if (reversing) "11" else "241"
            ),
            topPackage = sim("com.google.android.apps.maps", "sim"),
            radar = List(8) { i ->
                if (reversing) sim((20 + (i * 7 + tick * 3) % 120), "sim")
                else SignalValue.unknown(SignalSource.SIMULATION)
            },
            doors = List(5) { i -> sim(phase < 5 && i == 0, "0") },
            turnLights = listOf(
                sim(leftBlink, if (leftBlink) "1" else "0"),
                sim(rightBlink, if (rightBlink) "1" else "0"),
                sim(false, "0"),
            ),
            mcuVersion = sim("SIMULATED-MCU", "sim"),
            productId = sim("M701(sim)", "sim"),
            buildDisplayId = sim("SIMULATION", "sim"),
        )
        return syntheticState
    }

    // ---------------------------------------------------------------- replay

    private var replayState = CarState(simulated = true)

    private fun nextReplayState(): CarState {
        val events = replay ?: return replayState
        val now = SystemClock.elapsedRealtime()
        // Apply everything up to the current virtual second, then wrap around.
        val virtualSecond = tick * (TICK_MS / 1000.0)
        while (replayIndex < events.size && events[replayIndex].elapsedS <= virtualSecond) {
            val e = events[replayIndex]
            replayState = applyReplay(replayState, e, now)
            replayIndex++
        }
        if (replayIndex >= events.size) {
            replayIndex = 0
            tick = 0
        }
        return replayState
    }

    private fun applyReplay(state: CarState, e: ReplayEvent, now: Long): CarState {
        fun <T> sv(v: T?): SignalValue<T> = if (v == null) {
            SignalValue(null, Availability.INVALID, SignalSource.SIMULATION, now, e.value)
        } else {
            SignalValue(v, Availability.VALID, SignalSource.SIMULATION, now, e.value)
        }
        return when (e.property) {
            "wits.acc" -> state.copy(acc = sv(SignalParsers.bool(e.value)))
            "wits.backcar" -> state.copy(reverse = sv(SignalParsers.bool(e.value)))
            "wits.brake" -> state.copy(brake = sv(SignalParsers.bool(e.value)))
            "wits.ill" -> state.copy(illumination = sv(SignalParsers.bool(e.value)))
            "wits.source" -> state.copy(source = sv(SignalParsers.int(e.value)))
            "wits.top.package" -> state.copy(topPackage = sv(SignalParsers.string(e.value)))
            "wits.battery.vol" -> state.copy(batteryVoltageRaw = sv(SignalParsers.float(e.value)))
            "car.speed", "can.speed" -> state.copy(speedRaw = sv(SignalParsers.int(e.value)))
            "car.rate" -> state.copy(rpmRaw = sv(SignalParsers.int(e.value)))
            "vendor.can.angle" -> state.copy(steeringAngleRaw = sv(SignalParsers.int(e.value)))
            else -> state
        }.copy(simulated = true)
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
