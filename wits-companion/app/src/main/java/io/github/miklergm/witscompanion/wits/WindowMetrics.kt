package io.github.miklergm.witscompanion.wits

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager

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

/**
 * Window bounds, without an API-30 call on a build that declares minSdk 29.
 *
 * `WindowMetrics` arrived in API 30. The head unit runs Android 13, so on the vehicle these
 * always take the modern path; minSdk 29 exists only so the app stays installable on an API-29
 * emulator for tiling work, and there `WindowMetrics` does not exist.
 *
 * The call sites were already wrapped in `runCatching`, which does swallow the resulting
 * `NoSuchMethodError` — but relying on that left the version handling implicit (and lint
 * legitimately flagging ten errors). The fallback is the real display size: on API 29 there is
 * no per-window equivalent, and an emulator running this app is effectively full-screen, so it
 * is the right approximation rather than a stub.
 */
@Suppress("DEPRECATION")
private fun Context.legacyDisplayBounds(): Rect? = runCatching {
    val point = Point()
    getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(point)
    Rect(0, 0, point.x, point.y)
}.getOrNull()

/** Bounds of this activity's own window, or null when the platform will not report them. */
fun Activity.currentWindowBounds(): Rect? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
    } else {
        legacyDisplayBounds()
    }

/** Bounds of the largest window the display can host, or null when unavailable. */
fun Context.maximumWindowBounds(): Rect? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        }.getOrNull()
    } else {
        legacyDisplayBounds()
    }
