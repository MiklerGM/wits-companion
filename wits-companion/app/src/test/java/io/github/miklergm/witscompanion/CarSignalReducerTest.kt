package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.carstate.Availability
import io.github.miklergm.witscompanion.carstate.BroadcastUpdate
import io.github.miklergm.witscompanion.carstate.CarSignalReducer
import io.github.miklergm.witscompanion.carstate.SignalSource
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.safety.Trigger
import io.github.miklergm.witscompanion.wits.WitsProperties
import io.github.miklergm.witscompanion.wits.WitsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reduction rules, exercised directly.
 *
 * These are plain JVM tests with no Robolectric and no poll loop — which is the point of
 * splitting [CarSignalReducer] out of `CarStateRepository`. Every case below used to require
 * a live repository on two threads to reach, so most of them were simply never covered.
 */
class CarSignalReducerTest {

    private fun props(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = pairs.toMap()
        return { name -> map[name] }
    }

    // ------------------------------------------------------- transport priority

    @Test
    fun `a broadcast may raise the reverse alarm`() {
        val r = CarSignalReducer()
        r.reduceProperties(props(WitsProperties.BACKCAR to "0"), now = 1_000L)
        assertEquals(false, r.state.reverse.value)

        r.reduceBroadcast(BroadcastUpdate.Reverse(active = true, raw = "1"), now = 1_100L)
        assertEquals("a broadcast must be able to raise an alarm", true, r.state.reverse.value)
        assertEquals(SignalSource.BROADCAST, r.state.reverse.source)
    }

    @Test
    fun `a broadcast may not clear a fresh property-backed reverse`() {
        // The attack this exists to stop: the receiver is EXPORTED with no sender
        // authentication, so any installed app can forge "reverse released".
        val r = CarSignalReducer(propertyTrustWindowMs = 5_000L)
        r.reduceProperties(props(WitsProperties.BACKCAR to "1"), now = 1_000L)
        assertEquals(true, r.state.reverse.value)

        r.reduceBroadcast(BroadcastUpdate.Reverse(active = false, raw = "0"), now = 2_000L)

        assertEquals(
            "the forged clear must not win over the property",
            true, r.state.reverse.value,
        )
        assertEquals(SignalSource.PROPERTY, r.state.reverse.source)
    }

    @Test
    fun `a broadcast may clear reverse once the property positive is no longer fresh`() {
        // The protection is deliberately time-bounded: it stops a forged clear from beating
        // live telemetry, not from ever being believed again.
        val r = CarSignalReducer(propertyTrustWindowMs = 5_000L)
        r.reduceProperties(props(WitsProperties.BACKCAR to "1"), now = 1_000L)

        r.reduceBroadcast(BroadcastUpdate.Reverse(active = false, raw = "0"), now = 20_000L)

        assertEquals(false, r.state.reverse.value)
        assertEquals(SignalSource.BROADCAST, r.state.reverse.source)
    }

    @Test
    fun `the property clears reverse on its own next poll`() {
        // Legitimate clearing must not be delayed by the anti-spoof rule — the trusted
        // transport carries the same news within one poll interval.
        val r = CarSignalReducer()
        r.reduceProperties(props(WitsProperties.BACKCAR to "1"), now = 1_000L)
        r.reduceProperties(props(WitsProperties.BACKCAR to "0"), now = 2_000L)
        assertEquals(false, r.state.reverse.value)
    }

    @Test
    fun `source BACKCAR gets the same protection as the reverse boolean`() {
        val r = CarSignalReducer(propertyTrustWindowMs = 5_000L)
        r.reduceProperties(props(WitsProperties.SOURCE to WitsSource.BACKCAR.toString()), now = 1_000L)

        r.reduceBroadcast(BroadcastUpdate.Source(mode = WitsSource.LAUNCHER, raw = "7"), now = 2_000L)

        assertEquals(WitsSource.BACKCAR, r.state.source.value)
    }

    @Test
    fun `non-safety signals simply take the most recent transport`() {
        val r = CarSignalReducer()
        r.reduceProperties(props(WitsProperties.ILL to "1"), now = 1_000L)
        r.reduceBroadcast(BroadcastUpdate.Illumination(on = false, raw = "0"), now = 2_000L)
        assertEquals("no asymmetry outside the safety signals", false, r.state.illumination.value)

        r.reduceProperties(props(WitsProperties.ILL to "1"), now = 3_000L)
        assertEquals(true, r.state.illumination.value)
    }

    @Test
    fun `evidence is per transport, so one does not erase the other`() {
        val r = CarSignalReducer()
        r.reduceBroadcast(BroadcastUpdate.Acc(on = true, raw = "1"), now = 1_000L)
        // A poll where wits.acc is unreadable must not wipe what the broadcast established.
        r.reduceProperties(props(), now = 1_500L)
        assertEquals(true, r.state.acc.value)
    }

    // ------------------------------------------------------------- read failure

    @Test
    fun `a transient unreadable property keeps the last value but lets it age`() {
        val r = CarSignalReducer()
        r.reduceProperties(props(WitsProperties.BACKCAR to "0"), now = 1_000L)

        // Telemetry stops. The value is retained for display...
        r.reduceProperties(props(), now = 2_000L)
        assertEquals(false, r.state.reverse.value)
        assertTrue(r.state.reverse.isKnown)

        // ...but it keeps its ORIGINAL timestamp, so it ages out of control-grade.
        assertEquals(1_000L, r.state.reverse.updatedAtElapsedRealtime)
        assertNull(
            "a retained reading must not authorise automatic actions forever",
            r.state.reverseActiveForControl(now = 60_000L, maxAgeMs = 5_000L),
        )
    }

    @Test
    fun `a reading past the stale timeout is marked STALE`() {
        val r = CarSignalReducer(staleTimeoutMs = 30_000L)
        r.reduceProperties(props(WitsProperties.BACKCAR to "0"), now = 1_000L)
        r.reduceProperties(props(), now = 90_000L)
        assertEquals(Availability.STALE, r.state.reverse.availability)
    }

    // ----------------------------------------------------- end-to-end with guard

    @Test
    fun `telemetry loss makes the guard fail closed while leaving the display intact`() {
        // The whole point of the freshness split, exercised through the real guard.
        var now = 1_000L
        val r = CarSignalReducer()
        val guard = ReverseGuard(
            releaseDelayMs = 0L, controlMaxAgeMs = 5_000L, clock = { now },
        )

        r.reduceProperties(props(WitsProperties.BACKCAR to "0"), now)
        now = 2_000L
        assertTrue("fresh negative authorises", guard.check(r.state, Trigger.AUTOMATIC).isAllowed)

        // Telemetry disappears; the retained reading ages.
        now = 60_000L
        r.reduceProperties(props(), now)
        assertFalse(
            "a stale negative must stop authorising",
            guard.check(r.state, Trigger.AUTOMATIC).isAllowed,
        )
        assertTrue("an explicit user action is still allowed", guard.check(r.state, Trigger.USER).isAllowed)
        assertEquals("and the dashboard still shows the last reading", false, r.state.reverseActive)
    }

    // ------------------------------------------------------------------- sundry

    @Test
    fun `speed falls back to the CAN name when the car name is absent`() {
        val r = CarSignalReducer()
        r.reduceProperties(props(WitsProperties.CAN_SPEED to "42"), now = 1_000L)
        assertEquals(42, r.state.speedRaw.value)

        r.reduceProperties(
            props(WitsProperties.CAR_SPEED to "7", WitsProperties.CAN_SPEED to "42"),
            now = 2_000L,
        )
        assertEquals("the car name wins when populated", 7, r.state.speedRaw.value)
    }

    @Test
    fun `reset drops evidence, not just the projected snapshot`() {
        // Leaving simulation must not let a pre-simulation reverse=true resurface.
        val r = CarSignalReducer()
        r.reduceBroadcast(BroadcastUpdate.Reverse(active = true, raw = "1"), now = 1_000L)
        assertEquals(true, r.state.reverse.value)

        r.reset()
        assertNull(r.state.reverse.value)

        // A later poll sees only what it actually read.
        r.reduceProperties(props(WitsProperties.BACKCAR to "0"), now = 2_000L)
        assertEquals(false, r.state.reverse.value)
    }

    @Test
    fun `an unparseable broadcast extra degrades to INVALID rather than being trusted`() {
        val r = CarSignalReducer()
        r.reduceBroadcast(BroadcastUpdate.Reverse(active = null, raw = "banana"), now = 1_000L)
        assertEquals(Availability.INVALID, r.state.reverse.availability)
        assertFalse(r.state.reverse.isKnown)
        assertEquals("banana", r.state.reverse.rawValue)
    }

    @Test
    fun `an unhandled broadcast leaves the state untouched`() {
        val r = CarSignalReducer()
        r.reduceProperties(props(WitsProperties.BACKCAR to "0"), now = 1_000L)
        val before = r.state
        assertEquals(before, r.reduceBroadcast(BroadcastUpdate.Unhandled, now = 2_000L))
    }
}
