package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.carstate.Availability
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.SignalParsers
import io.github.miklergm.witscompanion.carstate.SignalSource
import io.github.miklergm.witscompanion.carstate.SignalValue
import io.github.miklergm.witscompanion.logging.LogRedactor
import io.github.miklergm.witscompanion.safety.ActionRateLimiter
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.safety.SourceGuard
import io.github.miklergm.witscompanion.safety.Trigger
import io.github.miklergm.witscompanion.wits.WitsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signal semantics, guards, rate limiting, redaction and the caller encoding. */
class SafetyAndSignalTest {

    // ------------------------------------------------------------- signals

    private fun <T> valid(v: T, at: Long = 1_000L, src: SignalSource = SignalSource.PROPERTY) =
        SignalValue(v, Availability.VALID, src, at, v.toString())

    /**
     * A state where the polled property reports [v].
     *
     * Both the display field and the trusted-transport field are set, because that is what the
     * reducer produces for a property reading. Constructing only the display field models a
     * *broadcast*-sourced value, which deliberately cannot authorise anything.
     */
    private fun reverseFromProperty(v: Boolean, at: Long = 1_000L) = CarState(
        reverse = valid(v, at),
        reverseFromProperty = valid(v, at),
    )

    @Test
    fun `unknown is never rendered as zero`() {
        val s = SignalValue.unknown<Int>()
        assertFalse(s.isKnown)
        assertEquals("—", s.display())
        assertNull(s.value)
    }

    @Test
    fun `a real zero is distinguishable from unknown`() {
        val zero = SignalValue.of("0", SignalSource.PROPERTY, SignalParsers::int, now = 10L)
        assertTrue(zero.isKnown)
        assertEquals(0, zero.value)
        assertEquals("0", zero.display())
    }

    @Test
    fun `unparseable raw value becomes INVALID and keeps the raw text`() {
        val s = SignalValue.of("not-a-number", SignalSource.PROPERTY, SignalParsers::int, now = 10L)
        assertEquals(Availability.INVALID, s.availability)
        assertEquals("not-a-number", s.rawValue)
        assertFalse(s.isKnown)
    }

    @Test
    fun `range validation marks out-of-range values INVALID`() {
        val s = SignalValue.of(
            "500", SignalSource.PROPERTY, SignalParsers::int, validate = { it in 0..100 }, now = 10L
        )
        assertEquals(Availability.INVALID, s.availability)
        assertEquals(500, s.value)   // value retained for debugging
    }

    @Test
    fun `staleness downgrades VALID after the timeout`() {
        val fresh = valid(42, at = 1_000L)
        assertEquals(Availability.VALID, fresh.withStaleness(5_000L, now = 3_000L).availability)
        assertEquals(Availability.STALE, fresh.withStaleness(5_000L, now = 10_000L).availability)
    }

    @Test
    fun `staleness never upgrades an unknown signal`() {
        val unknown = SignalValue.unknown<Int>()
        assertEquals(Availability.UNKNOWN, unknown.withStaleness(1_000L, now = 999_999L).availability)
    }

    @Test
    fun `boolean parser accepts the firmware encodings`() {
        listOf("1", "true", "on", "yes").forEach { assertEquals(true, SignalParsers.bool(it)) }
        listOf("0", "false", "off", "no").forEach { assertEquals(false, SignalParsers.bool(it)) }
        assertNull(SignalParsers.bool("maybe"))
    }

    @Test
    fun `int parser tolerates whitespace and decimals`() {
        assertEquals(42, SignalParsers.int(" 42 "))
        assertEquals(42, SignalParsers.int("42.7"))
        assertNull(SignalParsers.int("abc"))
    }

    // -------------------------------------------------------- reverse guard

    @Test
    fun `reverse active blocks both user and automatic actions`() {
        val guard = ReverseGuard(clock = { 100_000L })
        val state = CarState(reverse = valid(true))
        assertFalse(guard.check(state, Trigger.USER).isAllowed)
        assertFalse(guard.check(state, Trigger.AUTOMATIC).isAllowed)
    }

    @Test
    fun `source BACKCAR alone is enough to block`() {
        val guard = ReverseGuard(clock = { 100_000L })
        val state = CarState(source = valid(WitsSource.BACKCAR))
        assertFalse(guard.check(state, Trigger.USER).isAllowed)
    }

    @Test
    fun `unknown reverse fails closed for automatic but allows explicit user action`() {
        val guard = ReverseGuard(clock = { 100_000L })
        val state = CarState()   // nothing known
        assertNull(state.reverseActive)
        assertFalse(
            "automatic must fail closed",
            guard.check(state, Trigger.AUTOMATIC).isAllowed,
        )
        assertTrue(
            "explicit user action is allowed but logged",
            guard.check(state, Trigger.USER).isAllowed,
        )
    }

    @Test
    fun `automatic restore waits for the release delay after reverse ends`() {
        var now = 0L
        val guard = ReverseGuard(releaseDelayMs = 1_500L, clock = { now })

        now = 1_000L
        guard.observe(CarState(reverse = valid(true)))

        now = 1_500L   // 500 ms after reverse was last active
        val notYet = reverseFromProperty(false)
        assertFalse(guard.check(notYet, Trigger.AUTOMATIC).isAllowed)

        now = 3_000L   // 2000 ms later
        assertTrue(guard.check(notYet, Trigger.AUTOMATIC).isAllowed)
    }

    // ------------------------------------------------- simulated telemetry

    /**
     * Simulation drives the dashboard from fabricated data — a 90 s loop that indicates,
     * brakes, parks and reverses, or a replayed capture file. None of it is evidence.
     *
     * Nothing on the control path used to notice: `CarState.simulated` existed but was read in
     * exactly one place, a banner on the Debug screen. So the guards were deciding real
     * actions on fiction in both directions.
     */
    @Test
    fun `simulated telemetry is never control-grade`() {
        // Field for field this is what a genuine property reading produces.
        val fabricated = reverseFromProperty(false, at = 1_000L).copy(simulated = true)
        val guard = ReverseGuard(releaseDelayMs = 0L, controlMaxAgeMs = 5_000L, clock = { 2_000L })

        assertNull(
            "a fabricated negative must not authorise anything",
            fabricated.reverseActiveForControl(now = 2_000L, maxAgeMs = 5_000L),
        )
        assertFalse(guard.check(fabricated, Trigger.AUTOMATIC).isAllowed)
        assertTrue(
            "an explicit user action is still the user's, as for any unknown",
            guard.check(fabricated, Trigger.USER).isAllowed,
        )
    }

    @Test
    fun `a fabricated reverse does not block a real user action`() {
        // The simulator reverses for 11 s of every 90 and sets source=BACKCAR with it, so the
        // Cockpit refused the user's own taps roughly an eighth of the time, by two separate
        // routes, on a vehicle that was standing still.
        val fabricated = CarState(
            simulated = true,
            reverse = valid(true, src = SignalSource.SIMULATION),
            source = valid(WitsSource.BACKCAR, src = SignalSource.SIMULATION),
        )
        val guard = ReverseGuard(releaseDelayMs = 0L, clock = { 2_000L })

        assertTrue(guard.check(fabricated, Trigger.USER).isAllowed)
        assertFalse(
            "automatic actions still fail closed — unknown, not safe",
            guard.check(fabricated, Trigger.AUTOMATIC).isAllowed,
        )
    }

    @Test
    fun `a fabricated reverse does not start the release delay`() {
        var now = 1_000L
        val guard = ReverseGuard(releaseDelayMs = 1_500L, controlMaxAgeMs = 5_000L, clock = { now })

        guard.observe(CarState(simulated = true, reverse = valid(true, src = SignalSource.SIMULATION)))
        now = 1_100L

        assertNull("the settle timer must not have started", guard.sinceReverseMs())
        assertTrue(
            "so a real automatic restore is not held off by a manoeuvre that never happened",
            guard.check(reverseFromProperty(false, at = now), Trigger.AUTOMATIC).isAllowed,
        )
    }

    @Test
    fun `a source switch is refused outright while simulation is active`() {
        // Every test in SourceGuard reads state.source, and under simulation that is a value on
        // a timer. A source switch physically changes what the head unit shows; with no reading
        // of the real source there is nothing to decide on.
        val guard = SourceGuard(ReverseGuard(releaseDelayMs = 0L, clock = { 0L }))
        val fabricated = CarState(
            simulated = true,
            source = valid(WitsSource.LAUNCHER, src = SignalSource.SIMULATION),
            reverseFromProperty = valid(false),
        )

        val verdict = guard.check(fabricated, WitsSource.CAN, Trigger.USER)
        assertFalse(verdict.isAllowed)
        assertTrue("$verdict", verdict.reasonOrNull!!.contains("simulation"))
    }

    // --------------------------------------------------------- source guard

    @Test
    fun `source guard refuses to switch away from the reverse camera`() {
        val guard = SourceGuard(ReverseGuard(clock = { 0L }))
        val state = CarState(source = valid(WitsSource.BACKCAR), reverse = valid(true))
        val verdict = guard.check(state, WitsSource.LAUNCHER, Trigger.USER)
        assertFalse(verdict.isAllowed)
    }

    @Test
    fun `automatic OEM to Android switch is refused - must be explicit`() {
        val guard = SourceGuard(ReverseGuard(clock = { 0L }))
        val state = CarState(source = valid(WitsSource.CAN), reverse = valid(false))
        assertFalse(guard.check(state, WitsSource.LAUNCHER, Trigger.AUTOMATIC).isAllowed)
        assertTrue(guard.check(state, WitsSource.LAUNCHER, Trigger.USER).isAllowed)
    }

    @Test
    fun `switching to the current source is refused as a no-op`() {
        val guard = SourceGuard(ReverseGuard(clock = { 0L }))
        val state = CarState(source = valid(WitsSource.LAUNCHER), reverse = valid(false))
        assertFalse(guard.check(state, WitsSource.LAUNCHER, Trigger.USER).isAllowed)
    }

    // --------------------------------------------------------- rate limiter

    @Test
    fun `rate limiter enforces the minimum interval`() {
        var now = 0L
        val limiter = ActionRateLimiter(clock = { now })
        val limit = ActionRateLimiter.Limit(minIntervalMs = 5_000L, maxPerMinute = 10)

        assertTrue(limiter.check("k", limit).isAllowed)
        limiter.record("k")

        now = 1_000L
        assertFalse("too soon", limiter.check("k", limit).isAllowed)

        now = 6_000L
        assertTrue("interval elapsed", limiter.check("k", limit).isAllowed)
    }

    @Test
    fun `rate limiter enforces the per-minute cap`() {
        var now = 0L
        val limiter = ActionRateLimiter(clock = { now })
        val limit = ActionRateLimiter.Limit(minIntervalMs = 0L, maxPerMinute = 3)

        repeat(3) {
            assertTrue(limiter.check("s", limit).isAllowed)
            limiter.record("s")
            now += 1_000L
        }
        assertFalse("4th within the window is blocked", limiter.check("s", limit).isAllowed)

        now += 61_000L
        assertTrue("window slid", limiter.check("s", limit).isAllowed)
    }

    // ------------------------------------------------------- caller encoding

    @Test
    fun `source switch caller encodes the required 0xA7 tag`() {
        // Mirrors WitsSourceController.buildCaller without needing a Context.
        fun buildCaller(callerId: Int, recoverFlag: Int = 0): Int =
            -1493172224 or ((recoverFlag and 0xFF) shl 16) or (callerId and 0xFF)

        val caller = buildCaller(WitsSource.LAUNCHER)

        // CenterService.java:1878 checks (caller & 0xFF000000) == 0xA7000000
        assertEquals(
            "top byte must be 0xA7",
            0xA7000000.toInt(),
            caller and 0xFF000000.toInt(),
        )
        assertEquals("caller id in the low byte", WitsSource.LAUNCHER, caller and 0xFF)
        assertEquals("recover flag must be 0", 0, (caller and 0x00FF0000) shr 16)
        assertEquals(0xA70000F1.toInt(), caller)
    }

    @Test
    fun `recover flag lands in the middle byte when used`() {
        fun buildCaller(callerId: Int, recoverFlag: Int): Int =
            -1493172224 or ((recoverFlag and 0xFF) shl 16) or (callerId and 0xFF)

        val caller = buildCaller(WitsSource.LAUNCHER, recoverFlag = 2)
        assertEquals(2, (caller and 0x00FF0000) shr 16)
    }

    // ------------------------------------------------------------ redaction

    @Test
    fun `redactor masks mac addresses and vins`() {
        assertEquals("<MAC>", LogRedactor.redactValue("AA:BB:CC:DD:EE:FF"))
        assertEquals("<VIN>", LogRedactor.redactValue("WBAKS410X0C123456"))
        assertEquals("<ID>", LogRedactor.redactValue("356938035643809"))
    }

    @Test
    fun `sensitive keys are dropped from extras`() {
        val extras = mapOf(
            "serialno" to "ABC123",
            "wifi_password" to "hunter2",
            "bt_name" to "iPhone",
            "preset" to "maps65_spotify35",
        )
        val out = LogRedactor.redactExtras(extras)
        assertEquals("<redacted>", out["serialno"])
        assertEquals("<redacted>", out["wifi_password"])
        assertEquals("<redacted>", out["bt_name"])
        assertEquals("non-sensitive keys survive", "maps65_spotify35", out["preset"])
    }

    @Test
    fun `media metadata is redacted unless verbose is explicitly enabled`() {
        val extras = mapOf("title" to "Some Song", "artist" to "Some Artist")

        val default = LogRedactor.redactExtras(extras, verboseMedia = false)
        assertEquals("<redacted>", default["title"])
        assertEquals("<redacted>", default["artist"])

        val verbose = LogRedactor.redactExtras(extras, verboseMedia = true)
        assertEquals("Some Song", verbose["title"])
    }

    // -------------------------------------------------------- derived state

    @Test
    fun `reverseActive is true when any indicator fires`() {
        assertEquals(true, CarState(reverse = valid(true)).reverseActive)
        assertEquals(true, CarState(source = valid(WitsSource.BACKCAR)).reverseActive)
        assertEquals(false, CarState(reverse = valid(false)).reverseActive)
        assertNull("nothing known -> unknown", CarState().reverseActive)
    }

    // ------------------------------------------------- control-grade freshness

    @Test
    fun `stale negative reverse evidence stops authorising automatic actions`() {
        // The failure this exists to prevent: telemetry stops while reverse reads false, the
        // reading is retained as the last known value, and it keeps saying "safe" forever.
        val guard = ReverseGuard(controlMaxAgeMs = 5_000L, clock = { 100_000L })
        val longAgo = reverseFromProperty(false, at = 1_000L)   // 99 s old

        assertNull(
            "a negative that old is no longer evidence of anything",
            longAgo.reverseActiveForControl(now = 100_000L, maxAgeMs = 5_000L),
        )
        assertFalse(
            "automatic actions must fail closed on it",
            guard.check(longAgo, Trigger.AUTOMATIC).isAllowed,
        )
        assertTrue(
            "an explicit user action is still allowed, as for any unknown",
            guard.check(longAgo, Trigger.USER).isAllowed,
        )
    }

    @Test
    fun `fresh negative reverse evidence still authorises automatic actions`() {
        val guard = ReverseGuard(
            releaseDelayMs = 0L, controlMaxAgeMs = 5_000L, clock = { 4_000L },
        )
        val recent = reverseFromProperty(false, at = 1_000L)   // 3 s old
        assertEquals(false, recent.reverseActiveForControl(now = 4_000L, maxAgeMs = 5_000L))
        assertTrue(guard.check(recent, Trigger.AUTOMATIC).isAllowed)
    }

    @Test
    fun `a broadcast-sourced negative cannot authorise automatic actions`() {
        // The receiver is EXPORTED with no sender authentication, so any installed app can
        // forge reverse=false. Freshness alone would not stop it: a forged value arrives with
        // a brand-new timestamp, which is precisely how it would defeat the staleness rule.
        val forged = CarState(
            reverse = valid(false, at = 1_000L, src = SignalSource.BROADCAST),
            // reverseFromProperty deliberately left unknown: the trusted transport said nothing.
        )
        val guard = ReverseGuard(controlMaxAgeMs = 5_000L, clock = { 2_000L })

        assertNull(
            "a broadcast may not establish that reverse is safe",
            forged.reverseActiveForControl(now = 2_000L, maxAgeMs = 5_000L),
        )
        assertFalse(guard.check(forged, Trigger.AUTOMATIC).isAllowed)
        assertEquals("though the dashboard still shows it", false, forged.reverseActive)
    }

    @Test
    fun `a broadcast-sourced positive still raises the alarm`() {
        // The asymmetry: forging "reverse is on" only blocks our own automation, which is safe.
        val raised = CarState(reverse = valid(true, at = 1_000L, src = SignalSource.BROADCAST))
        val guard = ReverseGuard(clock = { 2_000L })
        assertEquals(true, raised.reverseActiveForControl(now = 2_000L, maxAgeMs = 5_000L))
        assertFalse(guard.check(raised, Trigger.USER).isAllowed)
    }

    @Test
    fun `positive reverse evidence never expires`() {
        // Asymmetric on purpose: a stale "camera is on" keeps blocking, a stale "camera is
        // off" stops authorising. Both directions fail safe.
        val guard = ReverseGuard(controlMaxAgeMs = 5_000L, clock = { 100_000L })
        val ancient = CarState(reverse = valid(true, at = 1_000L))
        assertEquals(true, ancient.reverseActiveForControl(now = 100_000L, maxAgeMs = 5_000L))
        assertFalse(guard.check(ancient, Trigger.USER).isAllowed)
        assertFalse(guard.check(ancient, Trigger.AUTOMATIC).isAllowed)

        val ancientSource = CarState(source = valid(WitsSource.BACKCAR, at = 1_000L))
        assertEquals(true, ancientSource.reverseActiveForControl(now = 100_000L, maxAgeMs = 5_000L))
    }

    @Test
    fun `a STALE reading is displayable but never control-grade`() {
        val stale = valid(false, at = 1_000L).withStaleness(5_000L, now = 40_000L)
        assertEquals(Availability.STALE, stale.availability)
        assertTrue("the UI may still show the last reading", stale.isKnown)
        assertFalse(
            "but it may not decide a control question",
            stale.isFreshFor(maxAgeMs = 60_000L, now = 40_000L),
        )
        assertNull(
            CarState(reverse = stale, reverseFromProperty = stale)
                .reverseActiveForControl(40_000L, 60_000L)
        )
    }

    @Test
    fun `display semantics are unchanged by the control-grade split`() {
        // reverseActive still reports the last known reading, however old — the dashboard
        // showing "no" is right where authorising an automatic action would not be.
        val old = reverseFromProperty(false, at = 1_000L)
        assertEquals(false, old.reverseActive)
    }

    @Test
    fun `android and oem source flags`() {
        assertEquals(true, CarState(source = valid(WitsSource.LAUNCHER)).androidSourceActive)
        assertEquals(true, CarState(source = valid(WitsSource.CAN)).oemSourceActive)
        assertNull(CarState().androidSourceActive)
    }

    @Test
    fun `door state is not guessed from an undecoded bitmask`() {
        // This BMW profile publishes doors as a packed mask in can.door ("ffffff80"),
        // not as per-door properties. Until that mask is decoded against physically
        // opening each door, claiming "a door is open" would be a guess [RUNTIME]/[HYP].
        val withMask = CarState(doorsRaw = valid("ffffff81"))

        assertNull("must not infer an answer from an undecoded mask", withMask.anyDoorOpen)
        assertTrue("but the raw value is carried for the explorer", withMask.doorsRaw.isKnown)
        assertEquals("ffffff81", withMask.doorsRaw.value)
    }

    @Test
    fun `packed pdc string is carried raw`() {
        val s = CarState(radarRaw = valid("2:0:0:4:0:0:0:0"))
        assertTrue(s.radarRaw.isKnown)
        assertEquals("2:0:0:4:0:0:0:0", s.radarRaw.value)
    }

    @Test
    fun `source names cover the safety-critical ids`() {
        assertEquals("OEM BMW", WitsSource.name(WitsSource.CAN))
        assertEquals("Android", WitsSource.name(WitsSource.LAUNCHER))
        assertEquals("Reverse camera", WitsSource.name(WitsSource.BACKCAR))
        assertEquals("unknown", WitsSource.name(null))
    }
}
