package io.github.miklergm.witscompanion

import io.github.miklergm.witscompanion.wits.WitsActions
import io.github.miklergm.witscompanion.wits.WitsProfile
import io.github.miklergm.witscompanion.wits.WitsProfile.SignalId
import io.github.miklergm.witscompanion.wits.WitsProfile.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the firmware profile against the drift it was created to stop.
 *
 * The pairings it holds — property, broadcast, extra, parser — used to live in four files with
 * nothing checking them against each other, so a signal could be parsed but never subscribed
 * to, or subscribed to and silently unparsed.
 */
class WitsProfileTest {

    // ------------------------------------------------------------- consistency

    @Test
    fun `every signal is reachable on at least one transport`() {
        WitsProfile.SIGNALS.forEach { signal ->
            assertTrue(
                "${signal.id} has neither a property nor a broadcast, so nothing can ever set it",
                signal.property != null || signal.actions.isNotEmpty(),
            )
        }
    }

    @Test
    fun `a signal that arrives by broadcast names the extras to read`() {
        WitsProfile.SIGNALS.filter { it.actions.isNotEmpty() }.forEach { signal ->
            assertTrue(
                "${signal.id} subscribes to ${signal.actions} but names no extra to read",
                signal.extras.isNotEmpty(),
            )
        }
    }

    @Test
    fun `no broadcast action is claimed by two signals`() {
        val actions = WitsProfile.SIGNALS.flatMap { it.actions }
        assertEquals(
            "an action mapped to two signals would make the receiver's lookup order significant",
            actions.size, actions.distinct().size,
        )
    }

    @Test
    fun `the subscribe list is exactly the actions the profile knows about`() {
        // The receiver whitelists on this list, so anything missing is silently never heard.
        assertEquals(WitsProfile.OBSERVED_ACTIONS, WitsActions.CAR_STATE_ACTIONS)
        assertEquals(
            "duplicate subscriptions would double-count in the diagnostics",
            WitsProfile.OBSERVED_ACTIONS.size, WitsProfile.OBSERVED_ACTIONS.distinct().size,
        )
    }

    @Test
    fun `observe-only actions are not also reduced signals`() {
        val reduced = WitsProfile.SIGNALS.flatMap { it.actions }.toSet()
        WitsProfile.OBSERVE_ONLY_ACTIONS.forEach {
            assertTrue("$it is listed as observe-only but also maps to a signal", it !in reduced)
        }
    }

    // ----------------------------------------------------------------- parsing

    @Test
    fun `each signal parses according to its declared type`() {
        val samples = mapOf(
            ValueType.BOOL to ("1" to true),
            ValueType.INT to ("42" to 42),
            ValueType.FLOAT to ("1.5" to 1.5f),
            ValueType.STRING to ("hello" to "hello"),
        )
        WitsProfile.SIGNALS.forEach { signal ->
            val (raw, expected) = samples.getValue(signal.type)
            assertEquals("${signal.id} (${signal.type})", expected, signal.parse(raw))
        }
    }

    @Test
    fun `unparseable input yields null rather than a fabricated value`() {
        val reverse = WitsProfile.signal(SignalId.REVERSE)
        assertNull(reverse.parse("banana"))
        assertNull(reverse.parse(null))
        // ...which the reducer turns into INVALID with the raw text kept, never into `false`.
    }

    @Test
    fun `reverse accepts both firmware encodings`() {
        // com.can.* carries an Int, com.real.* a Boolean; the receiver normalises before parsing.
        val reverse = WitsProfile.signal(SignalId.REVERSE)
        assertEquals(true, reverse.parse("1"))
        assertEquals(false, reverse.parse("0"))
        assertEquals(true, reverse.parse("true"))
        assertTrue(WitsActions.ACTION_REVSTATUS in reverse.actions)
        assertTrue(WitsActions.ACTION_REAL_REVSTATUS in reverse.actions)
    }

    @Test
    fun `speed declares its fallback in preference order`() {
        // car.speed is empty on this profile and can.speed carries it [RUNTIME].
        val speed = WitsProfile.signal(SignalId.SPEED)
        assertEquals(listOf("car.speed", "can.speed"), speed.propertyNames)
    }

    @Test
    fun `action lookup resolves to the owning signal`() {
        assertEquals(SignalId.REVERSE, WitsProfile.signalFor(WitsActions.ACTION_REVSTATUS)?.id)
        assertEquals(SignalId.SOURCE, WitsProfile.signalFor(WitsActions.ACTION_SOURCE_INFO)?.id)
        assertNull("observe-only actions have no signal", WitsProfile.signalFor(WitsActions.ACTION_KEY_CODE))
        assertNull(WitsProfile.signalFor("com.example.NOT_OURS"))
    }

    // -------------------------------------------------------------- doc drift

    /**
     * The properties the app actually reads must appear in the reference table.
     *
     * docs/car-state.md is the document a future reader trusts to know what this profile
     * publishes. A property added in code and not there is invisible; the audit asked for a
     * documentation-consistency gate, and this is the cheap half of it.
     */
    @Test
    fun `every property the profile reads is documented in car-state`() {
        val doc = docText("car-state.md")
        assertNotNull("docs/car-state.md not found from ${File(".").absolutePath} — the gate would pass vacuously", doc)
        val missing = WitsProfile.ALL_PROPERTIES.filter { !doc!!.contains(it) }
        assertTrue(
            "not documented in docs/car-state.md: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `the safety-critical signals are marked as such in the profile`() {
        // A future edit that drops the note should have to think about why.
        val reverse = WitsProfile.signal(SignalId.REVERSE)
        assertNotNull(reverse.note)
        assertTrue(reverse.note!!.contains("Safety-critical"))
    }

    private fun docText(name: String): String? = listOf(
        "../docs/$name", "../../docs/$name", "docs/$name",
    ).map(::File).firstOrNull { it.exists() }?.readText()
}
