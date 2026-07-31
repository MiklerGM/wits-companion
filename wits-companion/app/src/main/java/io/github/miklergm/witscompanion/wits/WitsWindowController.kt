package io.github.miklergm.witscompanion.wits

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.WindowManager
import io.github.miklergm.witscompanion.logging.EventLogger

/**
 * Sends `wits.intent.action.CHANGE_WINDOW` to the vendor hook in system_server.
 *
 * This is the ONLY window primitive the companion uses. It deliberately does not use
 * `MOVE_PIP_WINDOW`, which only manipulates the single `default_pip_app` task
 * (docs/window-management.md §5).
 *
 * The broadcast is fire-and-forget: system_server returns nothing, so success can only
 * be inferred out-of-band (`wits.top.package`, `getRunningTasks`).
 */
class WitsWindowController(
    private val appContext: Context,
    private val logger: EventLogger? = null,
) {

    data class WindowRequest(
        val packageName: String,
        val pixelBounds: Rect,
        val windowMode: Int = WitsWindowMode.FREEFORM,
    )

    /**
     * The usable display area in pixels: full display minus system bar insets.
     *
     * Uses [WindowManager.getMaximumWindowMetrics], **never**
     * `getCurrentWindowMetrics`. Current metrics describe the window this app is itself
     * drawn in, and the companion can be one of the tiles it is laying out. Measuring
     * from its own tile made every apply compute the layout inside the previous result:
     * `[RUNTIME]` 2026-07-31 the logged area went 2400 px → 840 px → 420 px over three
     * applies, squeezing both tiles into a corner. Maximum metrics are display-sized
     * regardless of how the app's own window is arranged.
     */
    @Suppress("DEPRECATION")
    fun usableArea(context: Context): Rect {
        val wm = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.maximumWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars()
            )
            return Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom,
            )
        }
        val dm = context.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /** Full display bounds, ignoring insets. Shown on the capability screen. */
    @Suppress("DEPRECATION")
    fun fullDisplayArea(context: Context): Rect {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect(wm.maximumWindowMetrics.bounds)
        } else {
            val dm = context.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    /**
     * Sends one CHANGE_WINDOW broadcast.
     *
     * @return true if the broadcast was dispatched (NOT that the window moved)
     */
    fun applyWindow(request: WindowRequest): Boolean {
        val intent = Intent(WitsActions.ACTION_CHANGE_WINDOW).apply {
            putExtra(WitsActions.EXTRA_PACKAGE_NAME, request.packageName)
            putExtra(WitsActions.EXTRA_WINDOW_MODE, request.windowMode)
            putExtra(WitsActions.EXTRA_LEFT, request.pixelBounds.left)
            putExtra(WitsActions.EXTRA_TOP, request.pixelBounds.top)
            putExtra(WitsActions.EXTRA_RIGHT, request.pixelBounds.right)
            putExtra(WitsActions.EXTRA_BOTTOM, request.pixelBounds.bottom)
        }

        return try {
            appContext.sendBroadcast(intent)
            logger?.log(
                category = "window",
                action = "change_window",
                packageName = request.packageName,
                extras = mapOf(
                    "mode" to WitsWindowMode.name(request.windowMode),
                    "bounds" to request.pixelBounds.flattenToString(),
                ),
                result = "sent",
                confidence = "HYP",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "CHANGE_WINDOW failed for ${request.packageName}", t)
            logger?.log(
                category = "window",
                action = "change_window",
                packageName = request.packageName,
                result = "error:${t.javaClass.simpleName}",
            )
            false
        }
    }

    /**
     * Starts a deep link so a later CHANGE_WINDOW can reposition the existing task.
     * Needed because the hook itself can only launch a package's MAIN activity.
     */
    fun startDeepLink(uri: String, packageName: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        logger?.log("window", "deep_link", packageName, result = "sent")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "deep link failed for $packageName: $uri", t)
        logger?.log("window", "deep_link", packageName, result = "error:${t.javaClass.simpleName}")
        false
    }

    /**
     * Plain launch of a package's MAIN activity, carrying freeform launch options.
     *
     * `CHANGE_WINDOW` assigns bounds but is **exclusive**: the vendor hook's warm path
     * (`getFreeformTaskId` → `startActivityFromRecents`) brings its own task forward and
     * drops every other freeform task to `visibleRequested=false`. A plain launch is
     * **inclusive** — it makes a task visible without hiding its neighbours.
     *
     * The launch alone is not enough either: with no options, a task that does not exist
     * yet is created fullscreen (opaque, so it occludes the other tile) or, for apps with
     * auto-PiP such as Maps, pinned. [ActivityOptions.setLaunchBounds] is public API and
     * puts the new task in freeform on a freeform-capable display;
     * `setLaunchWindowingMode` is `@hide`, so it is applied reflectively as a best effort
     * and its absence is not fatal.
     *
     * `[RUNTIME]` 2026-07-31 — geometry-for-all then launch-for-all is the only ordering
     * observed to leave both tiles `visible=true mode=freeform`; see research/window-debug/.
     */
    fun launchPackage(
        packageName: String,
        bounds: Rect? = null,
        windowMode: Int = WitsWindowMode.FREEFORM,
    ): Boolean = try {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) {
            appContext.startActivity(intent, launchOptions(bounds, windowMode))
            logger?.log(
                category = "window",
                action = "launch_package",
                packageName = packageName,
                extras = mapOf(
                    "mode" to WitsWindowMode.name(windowMode),
                    "bounds" to (bounds?.flattenToString() ?: "none"),
                ),
                result = "sent",
            )
            true
        } else {
            logger?.log("window", "launch_package", packageName, result = "no_launch_intent")
            false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "launch failed for $packageName", t)
        logger?.log("window", "launch_package", packageName, result = "error:${t.javaClass.simpleName}")
        false
    }

    /**
     * Freeform launch options, or null when no bounds are known.
     *
     * `setLaunchBounds` is public; `setLaunchWindowingMode` is hidden, so a failure to
     * reach it is logged at debug level and ignored — the bounds alone are usually enough
     * to land in freeform.
     */
    private fun launchOptions(bounds: Rect?, windowMode: Int): android.os.Bundle? {
        if (bounds == null || bounds.isEmpty) return null
        val options = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)
        runCatching {
            android.app.ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, windowMode)
        }.onFailure { Log.d(TAG, "setLaunchWindowingMode unavailable: ${it.javaClass.simpleName}") }
        return options.toBundle()
    }

    /** True when the package is installed and has a launcher activity. */
    fun isLaunchable(packageName: String): Boolean =
        runCatching {
            appContext.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)

    private companion object {
        const val TAG = "WitsWindowController"
    }
}
