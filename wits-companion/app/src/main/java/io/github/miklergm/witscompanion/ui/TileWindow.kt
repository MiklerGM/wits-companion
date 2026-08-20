package io.github.miklergm.witscompanion.ui

import android.app.Activity
import android.graphics.Rect
import android.view.WindowManager
import io.github.miklergm.witscompanion.wits.WitsWindowController
import kotlin.math.abs
import io.github.miklergm.witscompanion.wits.currentWindowBounds
import io.github.miklergm.witscompanion.wits.maximumWindowBounds

/** How wide our window must be, as a percentage of the display, to count as filling it (not a tile). */
private const val FILLS_DISPLAY_PERCENT = 90

/** Sub-pixel slack before [matchOwnTaskBounds] bothers to resize. */
private const val BOUNDS_THRESHOLD_PX = 4

/**
 * True when this activity's window is (near enough) as wide as the whole display — i.e. it is
 * standalone / full-screen, not one of the Cockpit's freeform tiles.
 *
 * Both the Cockpit panel and the config host answer this the same way — a full-width window floors
 * its top inset with the vendor strip height and reserves the map strip; a narrow tile does neither.
 * Defaults to true when the metrics cannot be read (assume full-screen, the safe default for inset
 * flooring).
 */
fun Activity.fillsDisplay(): Boolean {
    val own = currentWindowBounds()?.width() ?: return true
    val display = maximumWindowBounds()?.width() ?: return true
    return display > 0 && own * 100 >= display * FILLS_DISPLAY_PERCENT
}

/**
 * Resize this activity's **own** task (by [Activity.getTaskId]) to [target] when it is off by more
 * than [BOUNDS_THRESHOLD_PX] on any edge.
 *
 * An activity brought up as a Cockpit tile cannot rely on `ActivityOptions.setLaunchBounds` — that
 * is ignored once the task already exists, so a re-order to front (or a relaunch) arrives at the old
 * bounds, typically full-screen. Correcting its own task by id is how both the panel
 * ([DashboardActivity]) and the config ([MainActivity]) actually become tiles. Privileged path only;
 * a no-op otherwise (on the emulator the launch bounds already take).
 *
 * @return true if a resize was issued.
 */
fun Activity.matchOwnTaskBounds(controller: WitsWindowController, target: Rect): Boolean {
    if (!controller.isPrivileged) return false
    val current = currentWindowBounds() ?: return false
    val off = abs(current.left - target.left) > BOUNDS_THRESHOLD_PX ||
        abs(current.top - target.top) > BOUNDS_THRESHOLD_PX ||
        abs(current.width() - target.width()) > BOUNDS_THRESHOLD_PX ||
        abs(current.height() - target.height()) > BOUNDS_THRESHOLD_PX
    if (off) controller.resizeTaskTo(taskId, target)
    return off
}
