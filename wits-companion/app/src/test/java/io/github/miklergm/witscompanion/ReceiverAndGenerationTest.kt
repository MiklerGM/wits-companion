package io.github.miklergm.witscompanion

import android.content.Intent
import io.github.miklergm.witscompanion.carstate.BroadcastUpdate
import io.github.miklergm.witscompanion.carstate.WitsBroadcastReceiver
import io.github.miklergm.witscompanion.wits.WitsActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Guards for the two defects found on 2026-07-31:
 *  - the car-state receiver was registered NOT_EXPORTED and so never received the
 *    cross-process vendor broadcasts it exists for;
 *  - delayed window sends had no generation token, so a retry from a superseded layout
 *    could still fire after reverse engaged or the source left Android.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiverAndGenerationTest {

    private fun receiverSource(): String {
        val candidates = listOf(
            "src/main/java/io/github/miklergm/witscompanion/carstate/WitsBroadcastReceiver.kt",
            "app/src/main/java/io/github/miklergm/witscompanion/carstate/WitsBroadcastReceiver.kt",
        )
        val f = candidates.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("receiver source not found", f)
        return f!!.readText()
    }

    // ------------------------------------------------------------- receiver flags

    @Test
    fun `car state receiver is registered EXPORTED so vendor broadcasts arrive`() {
        val src = receiverSource()
        assertTrue(
            "must register EXPORTED: the senders (com.wits.pms, SystemUI) are other processes",
            src.contains("RECEIVER_EXPORTED"),
        )
        assertTrue(
            "must not use NOT_EXPORTED in the registration call",
            !src.contains("ContextCompat.RECEIVER_NOT_EXPORTED"),
        )
    }

    @Test
    fun `receiver drops actions outside the whitelist`() {
        var got: Pair<String, BroadcastUpdate>? = null
        val r = WitsBroadcastReceiver { a, u -> got = a to u }

        r.onReceive(null, Intent("com.evil.SOMETHING").putExtra("status", 1))
        assertNull("an unlisted action must be ignored entirely", got)
        assertEquals(0L, r.receivedCount)
    }

    @Test
    fun `receiver accepts a whitelisted action and parses its extra`() {
        var got: Pair<String, BroadcastUpdate>? = null
        val r = WitsBroadcastReceiver { a, u -> got = a to u }

        r.onReceive(null, Intent(WitsActions.ACTION_ILL_INFO).putExtra("status", 1))

        assertNotNull(got)
        assertEquals(WitsActions.ACTION_ILL_INFO, got!!.first)
        assertEquals(true, (got!!.second as BroadcastUpdate.Illumination).on)
        assertEquals(1L, r.receivedCount)
    }

    @Test
    fun `receiver handles both Int and Boolean reverse encodings`() {
        val seen = mutableListOf<BroadcastUpdate>()
        val r = WitsBroadcastReceiver { _, u -> seen += u }

        r.onReceive(null, Intent(WitsActions.ACTION_REVSTATUS).putExtra("REVSTATUS", 1))
        r.onReceive(null, Intent(WitsActions.ACTION_REAL_REVSTATUS).putExtra("REVSTATUS", true))

        assertEquals(2, seen.size)
        seen.forEach { assertEquals(true, (it as BroadcastUpdate.Reverse).active) }
    }

    @Test
    fun `a malformed extra degrades to null instead of being trusted`() {
        var got: BroadcastUpdate? = null
        val r = WitsBroadcastReceiver { _, u -> got = u }

        r.onReceive(null, Intent(WitsActions.ACTION_SOURCE_INFO).putExtra("source_mode", "not-a-number"))

        assertNull("unparseable value must not become a trusted number",
            (got as BroadcastUpdate.Source).mode)
        assertEquals("but the raw text is kept", "not-a-number", (got as BroadcastUpdate.Source).raw)
    }

    @Test
    fun `an over-long string extra is truncated`() {
        var got: BroadcastUpdate? = null
        val r = WitsBroadcastReceiver { _, u -> got = u }
        val huge = "x".repeat(10_000)

        r.onReceive(null, Intent(WitsActions.ACTION_ACC_INFO).putExtra("status", huge))

        val raw = (got as BroadcastUpdate.Acc).raw
        assertTrue("must be bounded", (raw?.length ?: 0) <= 512)
    }

    @Test
    fun `diagnostics distinguish never-seen from seen`() {
        val r = WitsBroadcastReceiver { _, _ -> }
        assertNull("nothing seen yet", r.ageOfLastEventMs())
        assertTrue(r.diagnostics().values.all { it == null })

        r.onReceive(null, Intent(WitsActions.ACTION_ACC_INFO).putExtra("status", 1))

        assertNotNull("now we have an age", r.ageOfLastEventMs())
        assertNotNull(r.diagnostics()[WitsActions.ACTION_ACC_INFO])
        assertNull("other actions still never seen",
            r.diagnostics()[WitsActions.ACTION_ILL_INFO])
    }

    // ---------------------------------------------------------- generation token

    private fun engineSource(): String {
        val candidates = listOf(
            "src/main/java/io/github/miklergm/witscompanion/layout/LayoutEngine.kt",
            "app/src/main/java/io/github/miklergm/witscompanion/layout/LayoutEngine.kt",
        )
        return File(candidates.first { File(it).exists() }).readText()
    }

    @Test
    fun `every delayed send is gated by the generation token`() {
        val src = engineSource()
        assertTrue("engine must keep a generation", src.contains("AtomicLong"))
        assertTrue("initial pass must be gated", src.contains("stillValid(myGeneration, \"initial\""))
        assertTrue("retry pass must be gated", src.contains("stillValid(myGeneration, \"retry\""))
    }

    @Test
    fun `stillValid re-checks the reverse guard at fire time, not only at request time`() {
        val src = engineSource()
        val body = src.substringAfter("private fun stillValid(").substringBefore("\n    }")
        assertTrue(
            "must consult the reverse guard when the callback actually fires",
            body.contains("reverseGuard.check"),
        )
        assertTrue("and abandon the rest of the sequence", body.contains("cancelPending()"))
    }

    @Test
    fun `cancelPending invalidates in-flight callbacks, not just the queue`() {
        val src = engineSource()
        val body = src.substringAfter("fun cancelPending()").substringBefore("\n    }")
        assertTrue(
            "bumping the generation is what stops a callback already dispatched",
            body.contains("generation.incrementAndGet()"),
        )
        assertTrue(body.contains("removeCallbacksAndMessages"))
    }

    @Test
    fun `reverse engaging mid-sequence cancels pending work`() {
        val src = engineSource()
        val body = src.substringAfter("fun onCarState(").substringBefore("\n    }")
        assertTrue("reverse must abort the sequence", body.contains("reverseActive == true"))
        assertTrue(body.contains("cancelPending()"))
    }

    @Test
    fun `leaving the Android source cancels pending work`() {
        val f = listOf(
            "src/main/java/io/github/miklergm/witscompanion/layout/LayoutRecoveryCoordinator.kt",
            "app/src/main/java/io/github/miklergm/witscompanion/layout/LayoutRecoveryCoordinator.kt",
        ).first { File(it).exists() }
        val src = File(f).readText()
        assertTrue(
            "the coordinator must cancel when the source stops being Android",
            src.contains("left_android_source") && src.contains("engine.cancelPending()"),
        )
        assertTrue("and feed state to the engine", src.contains("engine.onCarState(state)"))
    }

    @Test
    fun `a source switch cancels queued layout work before the screen changes hands`() {
        val f = listOf(
            "src/main/java/io/github/miklergm/witscompanion/wits/WitsSourceController.kt",
            "app/src/main/java/io/github/miklergm/witscompanion/wits/WitsSourceController.kt",
        ).first { File(it).exists() }
        val src = File(f).readText()
        assertTrue("hook must exist", src.contains("onBeforeSwitch"))
        val order = src.indexOf("onBeforeSwitch?.invoke()")
        val send = src.indexOf("sendBroadcast(")
        assertTrue("cancellation must happen before the broadcast", order in 1 until send)
    }
}
