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
        var existing = findTask(packageName)

        // A live task that is NOT freeform cannot be tiled by either route: resizeTask only
        // moves an already-freeform task, and ActivityOptions.setLaunchBounds is ignored for a
        // task that already exists. Launching over it produces a freeform task at whatever size
        // it had — full display — which then covers the panel, and no amount of re-applying
        // fixes it because the stale bounds belong to the task.
        //
        // `[RUNTIME]` 2026-08-21: exactly this, after a reinstall left Maps fullscreen. Only a
        // force-stop cleared it.
        //
        // The cost is real and worth stating: removing the task ends whatever it was doing,
        // including a live navigation route. It is accepted because the alternative observed on
        // the vehicle was a Cockpit that could not be used at all, and because this only fires
        // when the task is not freeform — which means the Cockpit was not set up anyway. A
        // freeform task is still moved in place, so the route-preserving path is untouched.
        if (existing != null && existing.windowingMode != WitsWindowMode.FREEFORM) {
            val removed = removeTask(existing.taskId)
            logger?.log(
                "window", "remove_before_launch", packageName,
                extras = mapOf(
                    "taskId" to existing.taskId,
                    "mode" to WitsWindowMode.name(existing.windowingMode),
                ),
                result = if (removed) "removed" else "failed",
            )
            // Gone: the launch below now creates a fresh task, which does honour launch bounds.
            if (removed) existing = null
        }

        // Switching the Cockpit's floating app: the chosen app may already be a freeform task
        // hidden *behind* another at the same tile bounds (the previous floating app). resizeTask
        // moves it in place but never reorders, so it would stay hidden. A launch brings the task
        // to the front; the retry pass then re-asserts the exact bounds with resizeTask.
        if (bringToFront) {
            return if (launchIntoFreeform(packageName, bounds, windowMode)) {
                logger?.log("window", "launch_freeform", packageName, result = "front")
                PlaceResult.Launched
            } else {
                PlaceResult.Failed("bring-to-front launch failed")
            }
        }
        // Fast, flicker-free path — but ONLY for a tile that is already visible. resizeTask
        // moves bounds without changing visibility or z-order, so on an existing-but-hidden
        // freeform task (a leftover tile behind the launcher) it would reposition something
        // the user never sees — the app "does not open". Such a task must be launched to be
        // brought forward. `[RUNTIME]` 2026-08-01.
        if (existing != null && existing.windowingMode == WitsWindowMode.FREEFORM && existing.visible) {
            // Leaving freeform entirely (reset / exit) is NOT handled here: `setTaskWindowingMode`
            // is absent on this ROM, so a freeform task cannot be un-windowed in place. The engine
            // removes freeform tasks outright instead (removeFreeformTasks / removeTask), so every
            // request that reaches this fast path is a reposition — resizeTask, no flicker.
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
        // leave it as-is rather than sending it a MAIN intent. Only a VISIBLE task counts — a
        // hidden one (e.g. Maps backgrounded by the vendor Home button) is not being viewed, so it
        // must fall through and be brought forward; otherwise the Cockpit comes up with the map tile
        // still behind the launcher (`[RUNTIME]` 2026-08-11, exposed once the config stopped masking
        // it). A fresh user apply does not preserve, so it also falls through.
        if (existing != null && preserve && existing.visible) {
            logger?.log(
                "window", "preserve_in_place", packageName,
                extras = mapOf("taskId" to existing.taskId, "mode" to WitsWindowMode.name(existing.windowingMode)),
                result = "left_as_is",
            )
            return PlaceResult.PreservedInPlace
        }
        return if (launchIntoFreeform(packageName, bounds, windowMode)) {
            logger?.log(
                "window", "launch_freeform", packageName,
                extras = mapOf("bounds" to bounds.flattenToString()),
                result = "launched",
            )
            PlaceResult.Launched
        } else {
            PlaceResult.Failed("launch failed")
        }
    }

    /**
     * All root tasks, or *why not*.
     *
     * Every way this can fail returns [TaskObservation.Unavailable] with a distinguishable
     * reason rather than an empty list. Absence of a reading and a reading of absence lead
     * callers to opposite actions, and this is the only place that knows which one happened.
     */
    fun observeRootTasks(): TaskObservation {
        val svc = service() ?: return TaskObservation.Unavailable("no_service")
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val infos = svc.javaClass.getMethod("getAllRootTaskInfos").invoke(svc) as? List<Any?>
                ?: return TaskObservation.Unavailable("not_a_list")
            TaskObservation.Observed(infos.mapNotNull { info -> info?.let { readSnapshot(it) } })
        }.getOrElse {
            Log.d(TAG, "getAllRootTaskInfos failed: ${it.javaClass.simpleName}")
            TaskObservation.Unavailable("reflection:${it.javaClass.simpleName}")
        }
    }

    /**
     * The task hosting [packageName], or null — *including* when the screen could not be read.
     *
     * [place] treats null as "no task to reuse" and launches instead of resizing, which is the
     * safe reading of an unknown: a launch creates a task at the requested bounds, where acting
     * on a task we only assume exists could resize or remove the wrong window.
     */
    fun findTask(packageName: String): TaskSnapshot? =
        observeRootTasks().tasksOrEmpty.firstOrNull { it.packageName == packageName }

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
