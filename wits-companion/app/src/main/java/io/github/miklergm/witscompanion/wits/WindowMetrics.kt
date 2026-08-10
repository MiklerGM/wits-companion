package io.github.miklergm.witscompanion.wits

import android.content.Context

/**
 * The framework `status_bar_height` dimen in px — the height of the vendor top strip on this head
 * unit (a per-window `ITYPE_STATUS_BAR` overlay, `frame=[0,0][2400,99]`). 0 if the resource is
 * absent.
 *
 * Used as a **floor** in several places because this ROM intermittently reports a *zero* top inset
 * (the bar is a per-window overlay, not a display inset, and after the Cockpit's freeform churn the
 * decor can come back without it): [WitsWindowController.usableArea] floors the tile geometry with
 * it, and the activities floor their content padding with it. One definition so the three cannot
 * drift apart.
 */
fun Context.statusBarHeightPx(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}
