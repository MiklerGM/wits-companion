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
 * What the platform said when asked which tasks exist.
 *
 * The distinction is the whole type. A plain `List` collapsed three different answers into an
 * empty list — *this build cannot observe tasks*, *the reflective call failed*, and *the
 * platform looked and there is genuinely nothing there* — and callers could not tell them
 * apart. [LayoutEngine][io.github.miklergm.witscompanion.layout.LayoutEngine]`.verify()` read
 * the first two as the third: a reflection failure meant every expected tile was reported
 * missing, which made it tear down and relaunch a layout that was very possibly correct,
 * ending a live navigation route on the strength of a reading that never happened.
 *
 * Not observing and observing nothing are opposite conclusions. One says *do not act*; the
 * other says *act, everything is gone*.
 */
sealed interface TaskObservation {

    /** The platform answered. An empty [tasks] is a real reading: there is nothing there. */
    data class Observed(val tasks: List<PrivilegedWindowController.TaskSnapshot>) : TaskObservation

    /**
     * No reading happened, for [reason]. Nothing may be concluded about the screen — in
     * particular not that a window is missing.
     */
    data class Unavailable(val reason: String) : TaskObservation

    /**
     * The tasks, with "could not look" flattened to "nothing there".
     *
     * Deliberately verbose, and deliberately the only way to get that flattening: a caller
     * asking for it is stating that the two mean the same thing *for this decision*, and every
     * such claim is greppable by name. Legitimate where the fallback is to act on our own
     * recorded state — the unprivileged un-window path never has an observer at all, and
     * `parkStaleWindows` falls back to what it last applied. Not legitimate where the reading
     * is the evidence for a correction; use [Observed] explicitly there.
     */
    val tasksOrEmpty: List<PrivilegedWindowController.TaskSnapshot>
        get() = when (this) {
            is Observed -> tasks
            is Unavailable -> emptyList()
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
    fun rootTasks(): TaskObservation
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
