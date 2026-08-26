package io.github.miklergm.witscompanion

import android.graphics.Rect
import io.github.miklergm.witscompanion.layout.ExpectedTile
import io.github.miklergm.witscompanion.layout.LayoutVerdict
import io.github.miklergm.witscompanion.layout.LayoutVerification
import io.github.miklergm.witscompanion.wits.PrivilegedWindowController
import io.github.miklergm.witscompanion.wits.PrivilegedWindowController.TaskSnapshot
import io.github.miklergm.witscompanion.wits.TaskObservation
import io.github.miklergm.witscompanion.wits.WitsWindowController
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whether the screen matches what an apply intended.
 *
 * The case that motivated these: on 2026-08-21 the verifier logged `ok, tiles: 2` while Maps
 * covered the entire panel. It compared centres only, and a full-display window centres close
 * enough to a 65 % left tile to pass. A verifier that cannot see a failure cannot repair it —
 * so it declared the layout good and stopped trying, and re-applying never helped.
 */
@RunWith(RobolectricTestRunner::class)
class LayoutVerificationTest {

    private val maps = "com.google.android.apps.maps"

    /** The Cockpit's left tile on this unit: a 65/35 split under a 99 px status bar. */
    private val leftTile = ExpectedTile(maps, Rect(0, 99, 1560, 900))

    private fun task(
        pkg: String = maps,
        bounds: Rect = Rect(0, 99, 1560, 900),
        mode: Int = WitsWindowMode.FREEFORM,
        visible: Boolean = true,
    ) = TaskSnapshot(taskId = 1, packageName = pkg, windowingMode = mode, visible = visible, bounds = bounds)

    // ------------------------------------------------------- the observed bug

    @Test
    fun `a window covering the whole display is misplaced, not ok`() {
        // Exactly what was on screen: Maps freeform at full display over the panel.
        //   expected centre 780, actual centre 1200, slack 780 -> the centre test passed.
        // Only the size check catches it.
        val fullDisplay = task(bounds = Rect(0, 0, 2400, 900))
        assertTrue(
            "a full-display window must not be reported as a correctly placed tile",
            LayoutVerification.misplaced(leftTile, listOf(fullDisplay)),
        )
    }

    @Test
    fun `the panel tile is not fooled by a full-display window either`() {
        // The other half of the same screen: the panel is the narrower tile, so a full-display
        // window is even further from it.
        val panel = ExpectedTile("io.github.miklergm.witscompanion", Rect(1560, 99, 2400, 900))
        val full = task(pkg = "io.github.miklergm.witscompanion", bounds = Rect(0, 0, 2400, 900))
        assertTrue(LayoutVerification.misplaced(panel, listOf(full)))
    }

    // ------------------------------------------------------------ still passes

    @Test
    fun `a correctly placed tile is accepted`() {
        assertFalse(LayoutVerification.misplaced(leftTile, listOf(task())))
    }

    @Test
    fun `rounding and inset drift are tolerated`() {
        // The ROM rounds, and the status-bar inset moves the top edge. None of that should
        // trigger a re-assert: a verifier that fires on noise would fight the vendor stack.
        listOf(
            Rect(0, 99, 1560, 900),      // exact
            Rect(0, 0, 1558, 900),       // a couple of pixels narrow, no inset
            Rect(2, 99, 1562, 898),      // shifted a little
            Rect(0, 99, 1480, 900),      // 5 % narrow
        ).forEach { b ->
            assertFalse("$b should be accepted", LayoutVerification.misplaced(leftTile, listOf(task(bounds = b))))
        }
    }

    // ------------------------------------------------------------ other faults

    @Test
    fun `a missing task is misplaced`() {
        assertTrue(LayoutVerification.misplaced(leftTile, emptyList()))
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(pkg = "com.other.app"))))
    }

    @Test
    fun `a task in the wrong windowing mode is misplaced`() {
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(mode = WitsWindowMode.FULLSCREEN))))
    }

    @Test
    fun `an invisible task is misplaced`() {
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(visible = false))))
    }

    @Test
    fun `a tile on the wrong side is misplaced`() {
        // Right-hand tile geometry where the left was expected.
        assertTrue(LayoutVerification.misplaced(leftTile, listOf(task(bounds = Rect(1560, 99, 2400, 900)))))
    }

    @Test
    fun `misplacedTiles reports each offender`() {
        val panel = ExpectedTile("io.github.miklergm.witscompanion", Rect(1560, 99, 2400, 900))
        val tasks = listOf(task())   // maps correct, panel absent
        val wrong = LayoutVerification.misplacedTiles(listOf(leftTile, panel), tasks)
        assertTrue(wrong.map { it.packageName } == listOf(panel.packageName))
    }

    // ------------------------------------------------- placement precondition

    @Test
    fun `an existing fullscreen task must be relaunched, not resized`() {
        // resizeTask only moves an already-freeform task, and setLaunchBounds is ignored for a
        // task that exists — so this one cannot be tiled without being torn down first.
        assertTrue(LayoutVerification.needsRelaunch(maps, listOf(task(mode = WitsWindowMode.FULLSCREEN))))
    }

    @Test
    fun `an existing freeform task is moved in place`() {
        // The route that keeps a live navigation session intact — no relaunch.
        assertFalse(LayoutVerification.needsRelaunch(maps, listOf(task())))
    }

    @Test
    fun `an app with no task needs no teardown`() {
        // Nothing to remove; the launch will carry the bounds itself.
        assertFalse(LayoutVerification.needsRelaunch(maps, emptyList()))
    }

    // ------------------------------ not observing vs observing nothing

    /**
     * The second defect in this area, and the reason [LayoutVerdict] exists.
     *
     * `rootTasks()` returned a plain list, and returned an empty one for three different
     * answers: this build cannot observe tasks, the reflective call threw, and the platform
     * looked and found nothing. verify() read all three as the third — so a reflection failure
     * reported every tile missing and triggered a full re-apply, which on the privileged path
     * removes and relaunches tasks. A correct layout torn down, and a live route with it, on
     * the strength of a reading that never happened.
     */
    @Test
    fun `a screen that was never read is unverifiable, not wrong`() {
        val verdict = LayoutVerification.verdict(
            listOf(leftTile),
            TaskObservation.Unavailable("reflection:NoSuchMethodException"),
        )

        assertTrue("got $verdict", verdict is LayoutVerdict.Unverifiable)
        assertEquals(
            "the reason has to survive, or the log cannot tell the two cases apart",
            "reflection:NoSuchMethodException",
            (verdict as LayoutVerdict.Unverifiable).reason,
        )
    }

    @Test
    fun `an empty screen that was actually read is wrong`() {
        // Byte for byte the task list the failed read used to produce — opposite conclusion.
        val verdict = LayoutVerification.verdict(listOf(leftTile), TaskObservation.Observed(emptyList()))

        assertEquals(listOf(maps), (verdict as LayoutVerdict.Misplaced).tiles.map { it.packageName })
    }

    @Test
    fun `a correctly placed layout verifies ok`() {
        assertEquals(
            LayoutVerdict.Ok,
            LayoutVerification.verdict(listOf(leftTile), TaskObservation.Observed(listOf(task()))),
        )
    }

    @Test
    fun `a build with no observer reports unavailable rather than an empty screen`() {
        // The unprivileged path holds no MANAGE_ACTIVITY_TASKS, so it can never see a task.
        // That must not read as "the screen is empty".
        val controller = WitsWindowController(RuntimeEnvironment.getApplication())

        assertTrue(controller.observeTasks() is TaskObservation.Unavailable)
    }

    /**
     * Structural guard: the engine verifies through [LayoutVerification.verdict], which cannot
     * drop the unreadable case, and never through the list-shaped primitive, which can.
     */
    @Test
    fun `the engine does not verify through the list-shaped primitive`() {
        val engine = listOf(
            File("src/main/java/io/github/miklergm/witscompanion/layout/LayoutEngine.kt"),
            File("app/src/main/java/io/github/miklergm/witscompanion/layout/LayoutEngine.kt"),
        ).firstOrNull { it.isFile }
        assertTrue("LayoutEngine.kt not found (cwd=${File(".").absolutePath})", engine != null)

        val body = engine!!.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

        assertFalse(
            "LayoutEngine must call LayoutVerification.verdict(), not misplacedTiles() — the "
                + "latter cannot tell an unread screen from an empty one",
            body.contains("misplacedTiles("),
        )
    }

    // --------------------------------------------- decoding one task at a time

    /** Stands in for the platform's `RootTaskInfo`, which a unit test cannot construct. */
    @Suppress("unused")
    class FakeTaskInfo(
        @JvmField val taskId: Int = 7,
        @JvmField val isVisible: Boolean = true,
        @JvmField val bounds: Rect? = Rect(0, 99, 1560, 900),
        private val mode: Int? = WitsWindowMode.FREEFORM,
        @JvmField val childTaskNames: Array<String> = arrayOf("com.google.android.apps.maps/.Main"),
    ) {
        fun getWindowingMode(): Int? = mode
    }

    /** A schema that no longer carries the fields the decoder needs. */
    @Suppress("unused")
    class ChangedTaskInfo(@JvmField val taskId: Int = 7)

    @Test
    fun `a task that decodes is read exactly, not approximated`() {
        val snapshot = PrivilegedWindowController(RuntimeEnvironment.getApplication())
            .readSnapshot(FakeTaskInfo())

        assertEquals(7, snapshot!!.taskId)
        assertEquals(maps, snapshot.packageName)
        assertEquals(WitsWindowMode.FREEFORM, snapshot.windowingMode)
        assertTrue(snapshot.visible)
        assertEquals(Rect(0, 99, 1560, 900), snapshot.bounds)
    }

    /**
     * Every one of these fields used to have a fallback, and together they made an undecodable
     * task look like a real one that was non-freeform, invisible and zero-sized. The verifier
     * calls that misplaced and re-applies over a live route; `place()` reads "not freeform" as
     * licence to remove the task before relaunching. A reflection failure could tear down a
     * running app.
     */
    @Test
    fun `a task that does not decode is null, not a plausible default`() {
        val controller = PrivilegedWindowController(RuntimeEnvironment.getApplication())

        assertNull("a missing schema must not become FULLSCREEN/invisible/empty",
            controller.readSnapshot(ChangedTaskInfo()))
        assertNull("nor may an unreadable windowing mode",
            controller.readSnapshot(FakeTaskInfo(mode = null)))
        assertNull("nor unreadable bounds",
            controller.readSnapshot(FakeTaskInfo(bounds = null)))
    }

    @Test
    fun `a task with no resolvable package still decodes`() {
        // The one field that is legitimately absent; callers already read a null package as
        // "not one of ours", and failing the whole reading over it would be too strict.
        val snapshot = PrivilegedWindowController(RuntimeEnvironment.getApplication())
            .readSnapshot(FakeTaskInfo(childTaskNames = emptyArray()))

        assertNull(snapshot!!.packageName)
        assertEquals(WitsWindowMode.FREEFORM, snapshot.windowingMode)
    }
}
