package io.github.miklergm.witscompanion.layout

/**
 * When each window change is sent.
 *
 * Pure arithmetic, no Handler and no Android runtime, so a schedule can be asserted directly
 * instead of inferred from what a device did. It was already reachable from tests through
 * `LayoutEngine.scheduleFor` — a public method on a class that needs a Context, a window
 * controller and two guards to construct, for arithmetic that needs none of them.
 *
 * The shape is not arbitrary. A pass is **all geometry, then all launches**, because the two
 * primitives behave differently on this ROM: `CHANGE_WINDOW` takes the vendor hook's warm path
 * (`getFreeformTaskId` → `startActivityFromRecents`), which fronts its own task and drops every
 * other freeform task to `visibleRequested=false`, while a plain launch is inclusive but cannot
 * set a windowing mode for a task that does not exist yet. Interleaving them leaves only the
 * last window visible. `[RUNTIME]` 2026-07-31, verified by dumpsys — research/window-debug/.
 */
object LayoutSchedule {

    /** Gap between two CHANGE_WINDOW sends inside the geometry phase. */
    const val GEOMETRY_DELAY_MS = 250L

    /** Settle time after the last CHANGE_WINDOW, before the first launch. */
    const val PHASE_GAP_MS = 600L

    /** Gap between two launches inside the visibility phase. */
    const val LAUNCH_DELAY_MS = 700L

    /** Gap between two parked windows. */
    const val PARK_DELAY_MS = 250L

    /** Settle time after the anchor panel is fronted, before the tiles are placed. */
    const val ANCHOR_SETTLE_MS = 450L

    const val DEFAULT_RETRIES = 1
    const val MAX_RETRIES = 2

    /**
     * Gaps measured from the END of the initial pass, not from `apply()`.
     * docs/window-management.md §7.
     */
    val RETRY_DELAYS_MS = listOf(600L, 1_600L)

    /**
     * When the post-apply verification runs, measured from the END of the pass — and, by its
     * length, how many corrections are allowed (2). Deliberately later than [RETRY_DELAYS_MS]:
     * the failure it targets is "freeform was not ready yet", which the blind retries are too
     * early to catch. Each correction relaunches nothing but does re-assert, so the budget stays
     * small — an unbounded loop would fight the vendor stack.
     */
    val VERIFY_DELAYS_MS = listOf(3_000L, 8_000L)

    /**
     * When the visibility phase starts, relative to the start of a pass: after every
     * `CHANGE_WINDOW` has been sent, plus a settle gap.
     */
    fun launchPhaseStart(windowCount: Int): Long =
        (windowCount - 1).coerceAtLeast(0) * GEOMETRY_DELAY_MS + PHASE_GAP_MS

    /** Total length of one full pass (geometry phase + visibility phase). */
    fun passDuration(windowCount: Int): Long =
        launchPhaseStart(windowCount) + (windowCount - 1).coerceAtLeast(0) * LAUNCH_DELAY_MS

    /** When a retry pass starts, relative to the start of the initial pass. */
    fun retryPassStart(windowCount: Int, attempt: Int): Long =
        passDuration(windowCount) + RETRY_DELAYS_MS[attempt]

    /** When the verification for [attempt] runs, relative to the start of the pass. */
    fun verificationAt(windowCount: Int, attempt: Int): Long =
        passDuration(windowCount) + VERIFY_DELAYS_MS[attempt]

    /** How many retry passes [retries] actually buys. */
    fun retryPasses(retries: Int): Int = retries.coerceIn(0, MAX_RETRIES)

    /**
     * Every time, in ms from `apply()`, at which a window change is scheduled.
     *
     * [preparationMs] is what the apply spends before the first tile is touched: parking stale
     * windows, and the anchor settle for a preset that raises the panel first. **The whole
     * timeline shifts by it, retries and verification included.** They did not, and the omission
     * inverted the order in a reachable case: an anchored one-window layout with one stale
     * window put the initial launch at 1300 ms and the first retry at 1200 ms, so the retry
     * fired before the launch it exists to repair.
     */
    fun scheduleFor(windowCount: Int, retries: Int, preparationMs: Long = 0L): List<Long> {
        if (windowCount <= 0) return emptyList()
        val out = mutableListOf<Long>()
        // Initial pass: geometry for every tile, then a launch for every tile.
        repeat(windowCount) { i -> out += preparationMs + i * GEOMETRY_DELAY_MS }
        val launchBase = preparationMs + launchPhaseStart(windowCount)
        repeat(windowCount) { i -> out += launchBase + i * LAUNCH_DELAY_MS }
        // Retry passes: launches only, so a retry never hides a placed tile.
        repeat(retryPasses(retries)) { attempt ->
            val base = preparationMs + retryPassStart(windowCount, attempt)
            repeat(windowCount) { i -> out += base + i * LAUNCH_DELAY_MS }
        }
        return out
    }

    /**
     * What an apply spends before the first tile is touched.
     *
     * One [PARK_DELAY_MS] per window it has to park, plus [ANCHOR_SETTLE_MS] when the panel is
     * raised first. Everything the apply schedules is measured from the end of it.
     */
    fun preparation(parkedWindows: Int, anchored: Boolean): Long =
        parkedWindows * PARK_DELAY_MS + if (anchored) ANCHOR_SETTLE_MS else 0L
}
