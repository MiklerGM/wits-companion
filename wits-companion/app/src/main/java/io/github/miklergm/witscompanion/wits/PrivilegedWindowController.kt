package io.github.miklergm.witscompanion.wits

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import io.github.miklergm.witscompanion.logging.EventLogger

/**
 * The window path taken when the app holds `MANAGE_ACTIVITY_TASKS`, i.e. when it is signed
 * with the platform key (see tools/build-probe.sh and research/window-debug/probe-screen.png).
 *
 * It exists to remove the two weaknesses of the vendor `CHANGE_WINDOW` hook, both confirmed
 * on the device:
 *
 *  - **flicker.** CHANGE_WINDOW repositions a task through `startActivityFromRecents`, which
 *    brings it to the front and hides the other freeform tiles. `ActivityTaskManager`'s
 *    `resizeTask(taskId, bounds, RESIZE_MODE_SYSTEM)` moves a task **in place** — no
 *    front, no visibility change — so an already-freeform tile is repositioned silently.
 *  - **no feedback.** CHANGE_WINDOW is fire-and-forget; the app then reads `dumpsys` over
 *    adb to see what happened. `getAllRootTaskInfos()` returns the real taskId, package,
 *    windowing mode, visibility and bounds of every root task.
 *
 * Everything here is reflective: the classes are `@hide`, so they cannot be imported, but
 * the platform signature lifts the hidden-API blocklist so the calls actually resolve
 * (the probe confirmed `setLaunchWindowingMode` resolves). Every reflective call is
 * guarded; any failure degrades to "not available" and the caller falls back to the hook.
 */
class PrivilegedWindowController(
    private val appContext: Context,
    private val logger: EventLogger? = null,
) {

    data class TaskSnapshot(
        val taskId: Int,
        val packageName: String?,
        val windowingMode: Int,
        val visible: Boolean,
        val bounds: Rect,
    )

    sealed interface PlaceResult {
        /** An existing freeform task was moved in place — the good, flicker-free path. */
        data object Resized : PlaceResult
        /** No suitable task existed, so it was launched into freeform at the bounds. */
        data object Launched : PlaceResult
        /** A live task in another mode was left as-is because [place] was told to preserve it. */
        data object PreservedInPlace : PlaceResult
        data class Failed(val reason: String) : PlaceResult
    }

    /** True when the privileged path is usable: permission held and reflection resolves. */
    fun isAvailable(): Boolean {
        if (appContext.checkSelfPermission(PERM_MANAGE_TASKS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return service() != null
    }

    /**
     * Places [packageName] at [bounds].
     *
     * If it already has a freeform task, that task is resized in place — no flicker. If it
     * has no task, or one in the wrong windowing mode, it is launched into freeform at the
     * bounds directly (with the hidden-API windowing-mode option, which resolves under the
     * platform signature). Only the launch of a not-yet-running app moves anything to the
     * front; repositioning never does.
     */
    fun place(
        packageName: String,
        bounds: Rect,
        windowMode: Int,
        preserve: Boolean = false,
        bringToFront: Boolean = false,
    ): PlaceResult {
        val existing = findTask(packageName)

        // Fast, flicker-free reposition — ONLY a tile that is already visible and that we are NOT
        // being told to raise. resizeTask moves bounds without touching visibility or z-order, so on
        // a hidden task it would reposition something the user never sees. `[RUNTIME]` 2026-08-01.
        // (Un-windowing a freeform task in place is impossible here — `setTaskWindowingMode` is
        // absent — so the engine removes freeform tasks outright; every request here is a reposition.)
        if (!bringToFront && existing != null &&
            existing.windowingMode == WitsWindowMode.FREEFORM && existing.visible
        ) {
            return if (resizeTask(existing.taskId, bounds)) {
                logger?.log(
                    "window", "resize_task", packageName,
                    extras = mapOf("taskId" to existing.taskId, "bounds" to bounds.flattenToString()),
                    result = "moved",
                )
                PlaceResult.Resized
            } else {
                PlaceResult.Failed("resizeTask returned false")
            }
        }
        // A live task the user is actually looking at must not be reset on an automatic restore:
        // leave it as-is rather than sending it a MAIN intent. Only a VISIBLE, non-raised task
        // counts; a hidden one falls through and is raised below. `[RUNTIME]` 2026-08-11.
        if (!bringToFront && existing != null && preserve && existing.visible) {
            logger?.log(
                "window", "preserve_in_place", packageName,
                extras = mapOf("taskId" to existing.taskId, "mode" to WitsWindowMode.name(existing.windowingMode)),
                result = "left_as_is",
            )
            return PlaceResult.PreservedInPlace
        }
        // Raise an EXISTING freeform task — the Cockpit's floating app being switched to (it may sit
        // hidden behind the previous one), or one left behind the launcher by the vendor Home button
        // (visible=true but z-ordered behind, so resizeTask alone keeps it hidden). Move the already
        // rendered task to the front by id: startActivityFromRecents sends no MAIN intent, so there
        // is no redraw flash and no route reset (the "launcher peeks while Maps redraws" transient).
        // resizeTask then re-asserts the exact bounds. Falls back to a relaunch when the primitive is
        // unavailable, so behaviour never regresses. `[RUNTIME]` 2026-08-11.
        if (existing != null && existing.windowingMode == WitsWindowMode.FREEFORM) {
            if (moveToFront(existing.taskId, bounds)) {
                resizeTask(existing.taskId, bounds)
                logger?.log(
                    "window", "move_to_front", packageName,
                    extras = mapOf("taskId" to existing.taskId, "bounds" to bounds.flattenToString()),
                    result = "fronted",
                )
                return PlaceResult.Resized
            }
        }
        // No task, a non-freeform task, or the front primitive was unavailable: launch into freeform.
        return if (launchIntoFreeform(packageName, bounds, windowMode)) {
            logger?.log(
                "window", "launch_freeform", packageName,
                extras = mapOf("bounds" to bounds.flattenToString(), "front" to bringToFront),
                result = "launched",
            )
            PlaceResult.Launched
        } else {
            PlaceResult.Failed("launch failed")
        }
    }

    /** All root tasks, or an empty list if the read failed. */
    fun rootTasks(): List<TaskSnapshot> {
        val svc = service() ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val infos = svc.javaClass.getMethod("getAllRootTaskInfos").invoke(svc) as? List<Any?>
                ?: return emptyList()
            infos.mapNotNull { info -> info?.let { readSnapshot(it) } }
        }.getOrElse {
            Log.d(TAG, "getAllRootTaskInfos failed: ${it.javaClass.simpleName}")
            emptyList()
        }
    }

    fun findTask(packageName: String): TaskSnapshot? =
        rootTasks().firstOrNull { it.packageName == packageName }

    // ------------------------------------------------------------------ internals

    private fun readSnapshot(info: Any): TaskSnapshot? = runCatching {
        val cls = info.javaClass
        val taskId = cls.getField("taskId").getInt(info)
        val topActivity = runCatching { cls.getField("topActivity").get(info) }.getOrNull()
        val pkg = topActivity?.let {
            runCatching { it.javaClass.getMethod("getPackageName").invoke(it) as? String }.getOrNull()
        } ?: firstChildPackage(info, cls)
        val mode = runCatching {
            cls.getMethod("getWindowingMode").invoke(info) as? Int
        }.getOrNull() ?: WitsWindowMode.FULLSCREEN
        val visible = runCatching { cls.getField("isVisible").getBoolean(info) }.getOrDefault(false)
        val bounds = runCatching { cls.getField("bounds").get(info) as? Rect }.getOrNull() ?: Rect()
        TaskSnapshot(taskId, pkg, mode, visible, bounds)
    }.getOrNull()

    /** Falls back to the flattened child component names when topActivity is null. */
    private fun firstChildPackage(info: Any, cls: Class<*>): String? = runCatching {
        val names = cls.getField("childTaskNames").get(info) as? Array<*> ?: return null
        names.filterIsInstance<String>().firstOrNull()?.substringBefore('/')
    }.getOrNull()

    // NOTE `[RUNTIME]` 2026-08-07: there is deliberately no `setTaskWindowingMode` wrapper —
    // `IActivityTaskManager.setTaskWindowingMode` **does not exist on this ROM** (the reflective
    // call throws `NoSuchMethodException`; confirmed in the decompiled `IActivityTaskManager`,
    // whose task verbs are `moveTaskToRootTask`, `removeRootTasksInWindowingModes`, …). A task
    // therefore cannot be un-windowed *in place*; un-windowing goes through [removeFreeformTasks]
    // / [removeTask], which remove the task outright.

    /**
     * Removes every freeform root task — the Cockpit's tiles — in one call. This is the reliable
     * un-window primitive on this ROM (where `setTaskWindowingMode` is absent): the tiles stop being
     * freeform windows that draw over fullscreen apps, so the vendor launcher / our config screen /
     * the next navigation come up clean. Reflective
     * `IActivityTaskManager.removeRootTasksInWindowingModes(int[])`.
     *
     * It closes the windowed apps (they are relaunched when a layout is next applied) — an
     * acceptable trade for Exit (a deliberate reset) and Settings (a config visit), and the only
     * verb available here that actually clears the windowing.
     */
    fun removeFreeformTasks(): Boolean {
        val svc = service() ?: return false
        return runCatching {
            svc.javaClass.getMethod("removeRootTasksInWindowingModes", IntArray::class.java)
                .invoke(svc, intArrayOf(WitsWindowMode.FREEFORM))
            true
        }.getOrElse {
            Log.w(TAG, "removeRootTasksInWindowingModes failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * Removes a single task by id — the way to clear ONE stale window on this ROM (where
     * `setTaskWindowingMode` is absent, so a stale freeform task cannot be un-frozen; the old
     * park-to-fullscreen path re-*launched* it instead, piling up windows). Reflective
     * `IActivityTaskManager.removeTask(int)`.
     */
    fun removeTask(taskId: Int): Boolean {
        val svc = service() ?: return false
        return runCatching {
            svc.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(svc, taskId)
            true
        }.getOrElse {
            Log.w(TAG, "removeTask failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * Resizes a known task in place (bounds only, stays freeform). Public so an activity can
     * correct **its own** task's bounds by [android.app.Activity.getTaskId] — no package/class
     * disambiguation needed. Used by the Cockpit panel to shrink from full-screen to its tile,
     * because `ActivityOptions.setLaunchBounds` is ignored when the task already exists.
     */
    fun resizeTaskTo(taskId: Int, bounds: Rect): Boolean = resizeTask(taskId, bounds)

    private fun resizeTask(taskId: Int, bounds: Rect): Boolean {
        val svc = service() ?: return false
        return runCatching {
            // IActivityTaskManager.resizeTask(int taskId, Rect bounds, int resizeMode).
            svc.javaClass.getMethod(
                "resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType
            ).invoke(svc, taskId, bounds, RESIZE_MODE_SYSTEM)
            true
        }.getOrElse {
            Log.w(TAG, "resizeTask failed: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun launchIntoFreeform(packageName: String, bounds: Rect, windowMode: Int): Boolean =
        runCatching {
            val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
            val options = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)
            // Hidden setter; resolves under the platform signature (probe-confirmed).
            android.app.ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, windowMode)
            appContext.startActivity(intent, options.toBundle())
            true
        }.getOrElse {
            Log.w(TAG, "launchIntoFreeform failed: ${it.javaClass.simpleName}")
            false
        }

    /**
     * Brings an existing task to the front by id WITHOUT relaunching it — reflective
     * `IActivityTaskManager.startActivityFromRecents(taskId, options)`, the same primitive the vendor
     * CHANGE_WINDOW hook uses (getFreeformTaskId → startActivityFromRecents). No MAIN intent is sent,
     * so the app keeps its state (an active Maps route) and its already-rendered frame, avoiding the
     * redraw flash a relaunch causes. [bounds] land it in freeform at that rect. Returns false when
     * the call is unavailable, so [place] falls back to a relaunch and behaviour never regresses.
     */
    private fun moveToFront(taskId: Int, bounds: Rect): Boolean {
        val svc = service() ?: return false
        return runCatching {
            val options = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)
            runCatching {
                android.app.ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(options, WitsWindowMode.FREEFORM)
            }
            svc.javaClass.getMethod(
                "startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java
            ).invoke(svc, taskId, options.toBundle())
            true
        }.getOrElse {
            Log.w(TAG, "startActivityFromRecents failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /** `ActivityTaskManager.getService()` → IActivityTaskManager, or null if unreachable. */
    private fun service(): Any? = cachedService ?: runCatching {
        Class.forName("android.app.ActivityTaskManager")
            .getMethod("getService").invoke(null)
            .also { cachedService = it }
    }.getOrNull()

    @Volatile
    private var cachedService: Any? = null

    private companion object {
        const val TAG = "PrivWindowController"
        const val PERM_MANAGE_TASKS = "android.permission.MANAGE_ACTIVITY_TASKS"
        const val RESIZE_MODE_SYSTEM = 0
    }
}
