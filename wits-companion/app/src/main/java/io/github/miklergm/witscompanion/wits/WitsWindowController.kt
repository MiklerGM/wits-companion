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
    private val privileged: PrivilegedWindowController = PrivilegedWindowController(appContext, logger),
) {

    data class WindowRequest(
        val packageName: String,
        val pixelBounds: Rect,
        val windowMode: Int = WitsWindowMode.FREEFORM,
    )

    /**
     * True when the platform-signed path is active. When it is, geometry goes through
     * `resizeTask` (no flicker, no front) and the two-phase CHANGE_WINDOW dance in
     * [io.github.miklergm.witscompanion.layout.LayoutEngine] is unnecessary — see
     * [applyWindow]. Cached, because it cannot change without a reinstall.
     */
    val isPrivileged: Boolean by lazy { privileged.isAvailable() }

    /** Real task state, or empty on the unprivileged path. For feedback and diagnostics. */
    fun rootTasks(): List<PrivilegedWindowController.TaskSnapshot> =
        if (isPrivileged) privileged.rootTasks() else emptyList()

    /**
     * Removes every freeform tile in one privileged call — the reliable un-window on this ROM
     * (`setTaskWindowingMode` is absent here). Returns false on the unprivileged path, where the
     * caller falls back to the per-tile CHANGE_WINDOW hook.
     */
    fun removeFreeformTasks(): Boolean = isPrivileged && privileged.removeFreeformTasks()

    /** Resizes a task (typically the caller's own, by taskId) in place. Privileged path only. */
    fun resizeTaskTo(taskId: Int, bounds: Rect): Boolean =
        isPrivileged && privileged.resizeTaskTo(taskId, bounds)

    /** Removes a single task by id — clears one stale window. Privileged path only. */
    fun removeTask(taskId: Int): Boolean = isPrivileged && privileged.removeTask(taskId)

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
            // On this head unit the display-level metrics report zero decor insets — the
            // 99 px top bar (home / clock / recents) is a per-window inset, not a display
            // inset (`ITYPE_STATUS_BAR frame=[0,0][2400,99]`). Floor the top with the
            // framework `status_bar_height` so tiles do not land under it. `[RUNTIME]`
            // 2026-08-01 — regressed when the size source moved to maximumWindowMetrics.
            val top = maxOf(insets.top, context.statusBarHeightPx())
            return Rect(
                bounds.left + insets.left,
                bounds.top + top,
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
    /**
     * @param preserveLive when true, a live task that is not already a freeform tile is
     *   left untouched instead of being relaunched — the caller is restoring a running app
     *   and must not reset it (an active Maps route, an open menu).
     * @param bringToFront when true, the app is brought to the front even if it is already a
     *   visible freeform task — for switching the Cockpit's floating app, where the chosen app
     *   may be hidden behind the previous one at the same tile bounds.
     */
    fun applyWindow(
        request: WindowRequest,
        preserveLive: Boolean = false,
        bringToFront: Boolean = false,
    ): Boolean {
        // Privileged path: resizeTask moves an existing freeform tile in place, or the app
        // is launched straight into freeform. Either way there is no CHANGE_WINDOW and no
        // hiding of the other tiles, so the flicker is gone.
        if (isPrivileged) {
            return when (val r = privileged.place(
                request.packageName, request.pixelBounds, request.windowMode,
                preserve = preserveLive, bringToFront = bringToFront,
            )) {
                is PrivilegedWindowController.PlaceResult.Failed -> {
                    Log.w(TAG, "privileged place failed for ${request.packageName}: ${r.reason}")
                    false
                }
                else -> true
            }
        }

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
    ): Boolean {
        // On the privileged path applyWindow() has already made the tile visible — either
        // by resizing a live freeform task (which stays visible) or by launching it into
        // freeform. A second plain launch here would only bring it to the front and
        // reintroduce the very reordering the privileged path avoids, so skip it.
        if (isPrivileged) return true
        return launchViaPlainStart(packageName, bounds, windowMode)
    }

    private fun launchViaPlainStart(
        packageName: String,
        bounds: Rect?,
        windowMode: Int,
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
