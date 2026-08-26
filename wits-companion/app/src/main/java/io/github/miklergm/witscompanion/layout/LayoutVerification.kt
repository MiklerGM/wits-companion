package io.github.miklergm.witscompanion.layout

import android.graphics.Rect
import io.github.miklergm.witscompanion.wits.PrivilegedWindowController
import io.github.miklergm.witscompanion.wits.TaskObservation
import io.github.miklergm.witscompanion.wits.WitsWindowMode

/** One tile an apply intended to place, and where it intended to put it. */
data class ExpectedTile(val packageName: String, val bounds: Rect)

/**
 * What a post-apply check concluded.
 *
 * [Unverifiable] is the member that has to exist. Without it "the screen is wrong" and "the
 * screen could not be read" are the same value, and a verifier that cannot see reaches for the
 * repair it would use if everything were missing — a full re-apply, which on the privileged
 * path removes and relaunches tasks. Correcting on the strength of a reading that never
 * happened is worse than not correcting.
 */
sealed interface LayoutVerdict {

    /** Every expected tile is where the apply meant to put it. */
    data object Ok : LayoutVerdict

    /** These tiles are not. Re-asserting is warranted. */
    data class Misplaced(val tiles: List<ExpectedTile>) : LayoutVerdict

    /** The screen was not read, for [reason]. Nothing is known; correct nothing. */
    data class Unverifiable(val reason: String) : LayoutVerdict
}

/**
 * Deciding whether the screen matches what an apply intended.
 *
 * Pulled out of [LayoutEngine] so it can be tested against fabricated task lists. It had a
 * defect that only a real vehicle revealed, and only then because the screen was visibly wrong
 * while the log said otherwise — the sort of thing a unit test catches instantly once the
 * comparison is reachable at all.
 */
object LayoutVerification {

    /**
     * True when [tile] is not where the apply meant to put it.
     *
     * Checks, in order: the task exists, it is freeform, it is visible, it is on the right part
     * of the screen, and it is **about the right size**.
     *
     * That last one is not redundant. Comparing centres alone passed a window covering the whole
     * display: a full-width task centres at 1200 while the left tile of a 65/35 split centres at
     * 780, and the slack — half the tile width, 780 — swallowed the 420 px difference. The
     * verifier reported `ok` while Maps sat over the entire panel `[RUNTIME]` 2026-08-21. A
     * verifier that cannot see a failure cannot repair it, and this one then declared the
     * layout good and stopped trying.
     */
    fun misplaced(tile: ExpectedTile, tasks: List<PrivilegedWindowController.TaskSnapshot>): Boolean {
        val task = tasks.firstOrNull { it.packageName == tile.packageName } ?: return true
        if (task.windowingMode != WitsWindowMode.FREEFORM) return true
        if (!task.visible) return true

        // Wrong half of the screen: centres further apart than half the intended tile width.
        val slack = (tile.bounds.width() / 2).coerceAtLeast(MIN_CENTRE_SLACK_PX)
        if (kotlin.math.abs(task.bounds.centerX() - tile.bounds.centerX()) > slack) return true

        // Wrong size. Generous, because the ROM rounds and the status-bar inset moves the top
        // edge, but far tighter than the difference between a tile and the whole display.
        val expectedWidth = tile.bounds.width()
        if (expectedWidth > 0) {
            val drift = kotlin.math.abs(task.bounds.width() - expectedWidth).toFloat() / expectedWidth
            if (drift > WIDTH_TOLERANCE) return true
        }
        return false
    }

    /**
     * Whether the screen matches [expected], given whatever the platform actually said.
     *
     * The entry point for anything that will *act* on the answer. [misplacedTiles] takes a
     * plain list and so cannot distinguish an empty screen from an unread one; this can, and
     * refuses to call an unread screen wrong.
     */
    fun verdict(expected: List<ExpectedTile>, observation: TaskObservation): LayoutVerdict =
        when (observation) {
            is TaskObservation.Unavailable -> LayoutVerdict.Unverifiable(observation.reason)
            is TaskObservation.Observed -> misplacedTiles(expected, observation.tasks)
                .let { wrong -> if (wrong.isEmpty()) LayoutVerdict.Ok else LayoutVerdict.Misplaced(wrong) }
        }

    /** Which of [expected] are not where they should be, given a reading that did happen. */
    fun misplacedTiles(
        expected: List<ExpectedTile>,
        tasks: List<PrivilegedWindowController.TaskSnapshot>,
    ): List<ExpectedTile> = expected.filter { misplaced(it, tasks) }

    /**
     * Whether placing [packageName] needs the existing task torn down first.
     *
     * `resizeTask` only moves a task that is *already* freeform, and
     * `ActivityOptions.setLaunchBounds` is ignored for a task that already exists — so a live
     * fullscreen task cannot be put into a tile by either route. It becomes freeform at
     * whatever size it already had, which on this ROM means the whole display, and it then
     * covers the panel. Removing it first is what lets the relaunch take the bounds.
     *
     * `[RUNTIME]` 2026-08-21: this is what happened after a reinstall left Maps fullscreen;
     * re-applying could not fix it because the stale bounds belong to the task, and only a
     * force-stop cleared them.
     */
    fun needsRelaunch(
        packageName: String,
        tasks: List<PrivilegedWindowController.TaskSnapshot>,
    ): Boolean {
        val task = tasks.firstOrNull { it.packageName == packageName } ?: return false
        return task.windowingMode != WitsWindowMode.FREEFORM
    }

    /** Centre slack floor, so a very narrow tile still tolerates rounding. */
    const val MIN_CENTRE_SLACK_PX = 120

    /**
     * How far a tile's width may drift before it counts as misplaced.
     *
     * 20 %: comfortably above the ROM's rounding and inset differences, comfortably below the
     * 54 % by which a full-display window exceeds a 65 % tile.
     */
    const val WIDTH_TOLERANCE = 0.20f
}
