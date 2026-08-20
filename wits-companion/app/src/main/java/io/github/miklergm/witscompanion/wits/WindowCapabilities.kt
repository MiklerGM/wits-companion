package io.github.miklergm.witscompanion.wits

import android.graphics.Rect

/**
 * What the window backend can actually do, asked one operation at a time.
 *
 * These replace callers branching on `isPrivileged`. That boolean answered "which build am I?"
 * when every call site was really asking something narrower — *can I observe tasks?*, *can I
 * remove one?* — and the two are not the same question. On this ROM they happen to coincide,
 * because all of it comes from the same `MANAGE_ACTIVITY_TASKS` grant; on the next firmware,
 * or if a vendor hook ever grows one of these verbs, they will not. Naming them separately
 * means a call site says *why* it is branching, and stops a capability check in one place from
 * silently implying a different capability somewhere else.
 *
 * A capability that is unavailable is **null** rather than a stub that quietly does nothing,
 * so the compiler makes the caller acknowledge the fallback path.
 */

/** Outcome of a window operation that changes something. */
sealed interface WindowOutcome {
    data object Done : WindowOutcome

    /** The backend cannot do this at all — not a failure, an absence. */
    data class Unsupported(val operation: String) : WindowOutcome

    /** The backend tried and the platform refused. */
    data class Failed(val operation: String, val reason: String) : WindowOutcome

    val ok: Boolean get() = this is Done

    /** Reason for logging, or null when it succeeded. */
    val reasonOrNull: String?
        get() = when (this) {
            is Done -> null
            is Unsupported -> "unsupported:$operation"
            is Failed -> "failed:$operation:$reason"
        }
}

/**
 * Reads live task state.
 *
 * Required by anything that verifies rather than assumes — the post-apply check, and deciding
 * which stale windows exist. Without it the engine can send window changes but cannot find out
 * whether they took.
 */
fun interface TaskObserver {
    fun rootTasks(): List<PrivilegedWindowController.TaskSnapshot>
}

/**
 * Moves a task in place, **without bringing it to the front**.
 *
 * That distinction is the whole reason the privileged path exists: the vendor CHANGE_WINDOW
 * hook cannot reposition a window without reordering it, which is what made the Cockpit flicker
 * and what breaks a live navigation route.
 */
fun interface TaskResizer {
    fun resize(taskId: Int, bounds: Rect): WindowOutcome
}

/**
 * Tears tasks down.
 *
 * There is no "close window" verb in the vendor hook, so without this the only way to clear a
 * stale tile is to park it out of sight. See `LayoutEngine.parkStaleWindows`.
 */
interface TaskRemover {
    fun remove(taskId: Int): WindowOutcome

    /** Clears every freeform tile in one call — the reliable un-window on this ROM. */
    fun removeAllFreeform(): WindowOutcome
}
