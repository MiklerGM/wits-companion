package io.github.miklergm.witscompanion

import android.content.Intent
import io.github.miklergm.witscompanion.carstate.Availability
import io.github.miklergm.witscompanion.carstate.BroadcastUpdate
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.SignalSource
import io.github.miklergm.witscompanion.carstate.SignalValue
import io.github.miklergm.witscompanion.carstate.WitsBroadcastReceiver
import io.github.miklergm.witscompanion.layout.LayoutEngine
import io.github.miklergm.witscompanion.safety.ActionRateLimiter
import io.github.miklergm.witscompanion.safety.ReverseGuard
import io.github.miklergm.witscompanion.wits.WitsActions
import io.github.miklergm.witscompanion.wits.WitsWindowController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    private fun engineSource(): String = sourceOf("layout/LayoutEngine.kt")

    /** Occurrences of a literal substring — these tests count call sites. */
    private fun String.occurrences(needle: String): Int = split(needle).size - 1

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            "src/main/java/io/github/miklergm/witscompanion/$relative",
            "app/src/main/java/io/github/miklergm/witscompanion/$relative",
        )
        return File(candidates.first { File(it).exists() }).readText()
    }

    /**
     * The anchor panel is the screen used while driving. It may read and it may drive
     * media transport through MediaSession, but it must not write to the vendor stack.
     */
    @Test
    fun `the anchor panel never writes to the vendor stack`() {
        val src = sourceOf("ui/DashboardActivity.kt")
        val code = src.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        listOf(
            "sendBroadcast", "Settings.System.put", "Settings.Global.put", "Settings.Secure.put",
            "setStreamVolume", "adjustStreamVolume", "applyWindow", "switchSource", "setNightMode",
        ).forEach { forbidden ->
            assertTrue("the anchor panel must not call $forbidden", !code.contains(forbidden))
        }
    }

    /**
     * PDC and doors are deliberately out of scope: both are already on the cluster and
     * the HUD, and reversing hands the screen to the OEM source anyway.
     */
    @Test
    fun `the anchor panel shows no PDC or door state`() {
        val code = sourceOf("ui/DashboardActivity.kt")
            .lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        listOf("radarRaw", "doorsRaw", "anyDoorOpen").forEach {
            assertTrue("the anchor panel must not surface $it", !code.contains(it))
        }
    }

    /**
     * The route-continuation guarantee: an automatic restore must not relaunch an app
     * that is still running, because a MAIN intent can reset it (an active Maps route, an
     * open menu). The initial launch phase and the retry pass must both honour it.
     */
    @Test
    fun `a preserved-live package is repositioned but never relaunched`() {
        val src = engineSource()
        // The guarantee lives in the launch phase and the retry pass, keyed on preserveLive.
        assertTrue(
            "the launch phase must skip preserved-live packages",
            src.contains("window.packageName in preserveLive"),
        )
        // reassert() is the route-safe entry point and must compute the live set.
        val reassert = src.substringAfter("fun reassert(").substringBefore("\n    }")
        assertTrue("reassert must feed livePackages into preserveLive", reassert.contains("livePackages()"))

        // The geometry phase must pass the preserve flag through, so a live non-freeform
        // task is left as-is rather than relaunched by place().
        assertTrue(
            "the geometry phase must thread preserveLive into applyWindow",
            src.contains("preserveLive = preserve"),
        )
    }

    /**
     * The privileged controller must not relaunch a live task it was told to preserve. A
     * live task in another windowing mode is left in place; only a dead task is launched.
     */
    @Test
    fun `place preserves a live task in another mode instead of relaunching it`() {
        val src = sourceOf("wits/PrivilegedWindowController.kt")
        val place = src.substringAfter("fun place(").substringBefore("/** All root tasks")
        // The bringToFront path launches first on purpose (it is a separate, non-preserving
        // branch); the preserve short-circuit must sit before the FALLBACK launchIntoFreeform.
        assertTrue("preserve must short-circuit before the fallback launchIntoFreeform",
            place.indexOf("PreservedInPlace") in 0 until place.lastIndexOf("launchIntoFreeform"))
    }

    /**
     * Parking and removal are delayed mutations that outlive the apply which scheduled them.
     * A rapid A-then-B switch must not let A's cleanup tear down B's tiles.
     */
    @Test
    fun `stale-window cleanup is gated on the transaction generation`() {
        val src = engineSource()
        val park = src.substringAfter("private fun parkStaleWindows(").substringBefore("\n    }")

        // Both cleanup paths — task removal and the in-place park — are delayed.
        assertTrue(
            "removal is asked for by capability, not inferred from a build flag",
            park.contains("windowController.taskRemover") && park.contains("remover.remove(task.taskId)"),
        )
        assertTrue("the unprivileged path re-parks in place", park.contains("WindowRequest(pkg, parkBounds, parkMode)"))

        // Neither may fire unguarded: stillValid() re-checks both the generation and the
        // vehicle state at fire time.
        val delayed = park.occurrences("handler.postDelayed")
        assertEquals("every delayed mutation must be gated", delayed, park.occurrences("stillValid("))

        // ...and each must be cancellable by the next apply, which clears RETRY_TOKEN.
        assertEquals("every delayed mutation must carry the retry token", delayed, park.occurrences("RETRY_TOKEN"))

        // The generation has to reach the helper at all.
        assertTrue("the helper takes the generation", park.contains("myGeneration: Long"))
    }

    /** "Applied" must not claim windows that were never placed. */
    @Test
    fun `apply reports the tiles it actually dispatched`() {
        val src = engineSource()
        val apply = src.substringAfter("        scheduleVerification(preset, expected,")

        assertTrue(
            "the count comes from what was dispatched, not from the preset",
            apply.contains("Result.Applied(expected.size"),
        )
        assertTrue(
            "a layout with nothing launchable must refuse, not report success",
            apply.contains("expected.isEmpty()") && apply.contains("Result.Refused("),
        )
    }

    /**
     * The post-apply verification repairs a layout that did not take (cold boot: freeform not ready
     * yet). It must stay a bounded, guarded repair — never a standing watchdog that fights the user
     * or the vendor stack.
     */
    @Test
    fun `post-apply verification is bounded and guarded`() {
        val src = sourceOf("layout/LayoutEngine.kt")
        val schedule = src.substringAfter("private fun scheduleVerification(").substringBefore("\n    }")
        assertTrue(
            "bounded by the number of verify delays",
            schedule.contains("attempt >= VERIFY_DELAYS_MS.size"),
        )
        assertTrue(
            "requires task observation specifically — it must be able to read back what it sent",
            schedule.contains("windowController.taskObserver == null"),
        )

        val verify = src.substringAfter("private fun verifyPlacement(").substringBefore("\n    }")
        assertTrue("superseded applies must not correct", verify.contains("myGeneration != generation.get()"))
        assertTrue("never repair a layout the user exited", verify.contains("!layoutOwned"))
        assertTrue("never re-lay windows over the reverse camera", verify.contains("reverseGuard.check"))
        assertTrue("correction carries the attempt budget", verify.contains("verifyAttempt = attempt + 1"))
        assertTrue("correction is route-safe (repositions, not relaunches)", verify.contains("preserveLive"))

        // Exit / reset must drop ownership, or a queued verification would undo the exit.
        val unwindow = src.substringAfter("fun unwindowTiles(").substringBefore("\n    }")
        assertTrue("exit drops layout ownership", unwindow.contains("layoutOwned = false"))
    }

    /** Auto-starting our own panel is safe; doing it over the reverse camera is not. */
    @Test
    fun `cockpit autostart is opt-in and refused while reversing`() {
        val src = sourceOf("layout/LayoutRecoveryCoordinator.kt")
        // Bringing our own panel up (the no-last-layout fallback) never runs over the reverse camera.
        val body = src.substringAfter("fun openCockpitPanel(").substringBefore("\n    }")
        assertTrue("never over the reverse camera", body.contains("reverseActive"))
        // Autostart is opt-in: each trigger only fires when its toggle is on.
        assertTrue("boot gated on restoreOnBoot", src.contains("if (repository.restoreOnBoot) attempt"))
        assertTrue("ACC gated on restoreOnAcc", src.contains("if (repository.restoreOnAcc) attempt"))
        assertTrue("resume gated on restoreOnResume", src.contains("if (!repository.restoreOnResume) return false"))
    }

    /**
     * The reset must always end by bringing HOME to the front, even when there are no
     * tiles to park — that final step is what guarantees a clean vendor screen if a tile
     * refuses to move. It also cancels pending work first, so a queued apply cannot
     * re-tile behind the reset.
     */
    @Test
    fun `reset cancels pending work and always brings home to the front`() {
        val src = engineSource()
        val body = src.substringAfter("fun resetToVendorState()").substringBefore("\n    }\n")
        assertTrue("reset must cancel pending work first", body.contains("cancelPending()"))
        assertTrue("reset must bring the launcher forward", body.contains("goHome()"))
        val goHome = src.substringAfter("private fun goHome()").substringBefore("\n    }")
        assertTrue("goHome must use a standard HOME intent", goHome.contains("CATEGORY_HOME"))
    }

    @Test
    fun `the anchor is the dashboard, not the tabbed configuration activity`() {
        val src = engineSource()
        val body = src.substringAfter("private fun bringAnchorToFront(").substringBefore("\n    }")
        assertTrue(
            "bringAnchorToFront must start DashboardActivity",
            body.contains("DashboardActivity"),
        )
        assertTrue(
            "it must not fall back to the launcher activity",
            !body.contains("getLaunchIntentForPackage"),
        )
    }

    /**
     * `SessionRecorder.record()` notifies listeners synchronously, on whichever probe
     * thread produced the event, and wraps each call in `runCatching`. A listener that
     * touches a View from there throws `CalledFromWrongThreadException`, which is then
     * swallowed: recording works, the live timeline silently never updates.
     */
    @Test
    fun `the signal explorer refreshes its views on the UI thread`() {
        val src = sourceOf("ui/SignalExplorerSection.kt")
        val listener = src
            .substringAfter("SessionRecorder.Listener")
            .substringBefore("override fun onCreateView")
        assertTrue(
            "the recorder listener must marshal onto the main looper",
            listener.contains("ui.post") || listener.contains("getMainLooper"),
        )
    }

    /**
     * The companion can be one of the tiles in the layout it applies, so measuring from
     * its own window makes each apply compute the next layout inside the previous
     * result — the area shrank 2400 → 840 → 420 px on the device before this was fixed.
     */
    @Test
    fun `layout area is measured from the display, never from our own window`() {
        val src = sourceOf("wits/WitsWindowController.kt")
        val code = src.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "must not measure the layout area from the app's own window",
            !code.contains("currentWindowMetrics"),
        )
        assertTrue(
            "must measure from the display",
            code.contains("maximumWindowMetrics"),
        )
    }

    @Test
    fun `every delayed send is gated by the generation token`() {
        val src = engineSource()
        assertTrue("engine must keep a generation", src.contains("AtomicLong"))
        // Both phases of both the initial and the retry pass must re-check at fire time.
        listOf("geometry", "make_visible", "retry_visible").forEach { phase ->
            assertTrue(
                "phase \"$phase\" must be gated by the generation token",
                src.contains("stillValid(myGeneration, \"$phase\""),
            )
        }
    }

    /**
     * Guards the ordering discovered on the device: a launch must never be scheduled
     * from inside the callback that sends CHANGE_WINDOW, because that interleaves the
     * phases and the next CHANGE_WINDOW hides the tile just made visible.
     */
    @Test
    fun `the visibility phase is scheduled independently, not nested in the geometry send`() {
        val src = engineSource()
        val geometryCallback = src
            .substringAfter("stillValid(myGeneration, \"geometry\"")
            .substringBefore("offset + index * GEOMETRY_DELAY_MS")
        assertTrue(
            "launchPackage must not be nested inside the geometry callback",
            !geometryCallback.contains("launchPackage"),
        )
    }

    /**
     * The fire-time re-check must use the apply's ORIGINAL trigger, not a hard-coded
     * AUTOMATIC. A deliberate user tap should still place when reverse is merely unknown
     * (common — the property is not always readable); only automatic restores fail closed
     * on unknown. Hard-coding AUTOMATIC silently dropped user applies with no CAN data.
     */
    @Test
    fun `the fire-time reverse re-check honours the original trigger`() {
        val src = engineSource()
        val body = src.substringAfter("private fun stillValid(").substringBefore("\n    }")
        assertTrue(
            "stillValid must re-check with the stored applyTrigger",
            body.contains("reverseGuard.check(latestState, applyTrigger)"),
        )
        assertTrue(
            "apply must record its trigger for the re-check",
            src.contains("applyTrigger = trigger"),
        )
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

    /**
     * Simulated telemetry must not reach the automatic triggers at all.
     *
     * The layout triggers were already blocked downstream — an automatic apply needs
     * control-grade reverse evidence, which a simulated snapshot cannot supply — but
     * `restoreHotspotIfEnabled` consults no guard whatsoever, so a fabricated ACC OFF→ON edge
     * switched on a real Wi-Fi hotspot. That edge is reachable by ordinary use: observe a real
     * engine-off state, turn simulation on, and the simulator's first frame reports ACC on.
     */
    @Test
    fun `the recovery coordinator ignores simulated telemetry`() {
        val src = sourceOf("layout/LayoutRecoveryCoordinator.kt")
        val body = src.substringAfter("override fun onCarState(state: CarState) {")
            .substringBefore("\n    }")

        val bail = body.indexOf("if (state.simulated)")
        assertTrue("onCarState must check state.simulated", bail >= 0)
        assertTrue(
            "and must do it before anything acts on the state",
            bail < body.indexOf("reverseGuard.observe(state)"),
        )
        assertTrue("the pass has to end there", body.substringAfter("if (state.simulated)").contains("return"))
        assertTrue(
            "the other two state entry points refuse it too",
            src.contains("fun onActivityResumed(state: CarState): Boolean {\n        if (state.simulated) return false"),
        )
        assertTrue(src.contains("if (state.simulated) {\n            return LayoutEngine.Result.Refused"))
    }

    /**
     * The other half of the Cockpit's app toggle had no guard at all.
     *
     * `floatApp` documents going through the engine "so the reverse guard, the rate limiter and
     * the two-phase ordering all still apply". Tapping the *lit* tile called
     * `hideFloatingApp`, which moved the floating window and grew the panel to the full display
     * past all three — over the reverse camera, if that is what was under it.
     */
    @Test
    fun `hiding the floating app is refused while reversing`() {
        val context = RuntimeEnvironment.getApplication()
        val engine = LayoutEngine(
            appContext = context,
            windowController = WitsWindowController(context),
            reverseGuard = ReverseGuard(clock = { 2_000L }),
            rateLimiter = ActionRateLimiter(),
        )
        val reversing = CarState(
            reverse = SignalValue(true, Availability.VALID, SignalSource.PROPERTY, 1_000L, "1"),
        )

        val result = engine.hideFloatingApp("com.google.android.apps.maps", reversing)

        assertTrue("got $result", result is LayoutEngine.Result.Refused)
    }

    @Test
    fun `the refusal happens before anything is cancelled`() {
        // A refusal that has already torn down in-flight work is not a refusal.
        val src = sourceOf("layout/LayoutEngine.kt")
        val body = src.substringAfter("fun hideFloatingApp(").substringBefore("\n    }")
        assertTrue(
            "the guard must be checked before cancelPending()",
            body.indexOf("reverseGuard.check") < body.indexOf("cancelPending()"),
        )
    }

    @Test
    fun `the panel records itself hidden only when the engine agreed`() {
        val src = sourceOf("ui/DashboardActivity.kt")
        val body = src.substringAfter("private fun hideFloatingApp() {").substringBefore("\n    }")
        assertTrue(
            "cockpitLeft must be written inside the Applied branch, after the call",
            body.indexOf("hideFloatingApp(current") < body.indexOf("cockpitLeft = CockpitLeft.Hidden"),
        )
        assertTrue("a refusal has to reach the user", body.contains("Result.Refused"))
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
