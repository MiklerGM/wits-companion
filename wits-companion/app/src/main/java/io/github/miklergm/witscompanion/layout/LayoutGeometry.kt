package io.github.miklergm.witscompanion.layout

import android.graphics.Rect

/**
 * Where the Cockpit's two tiles go.
 *
 * Every one of these takes the display area as an argument rather than reading it from a
 * window controller, which is what makes them testable without a device — and what makes the
 * one rule below checkable at all.
 *
 * The rule: the app tile, the panel complement and the panel's own black-strip reservation all
 * derive from [appTile]. They cannot be allowed to disagree, because the panel resizes its own
 * task to the complement while the engine places the app in the tile, and a mismatch shows up
 * as a seam or an overlap on a 2400 px display where both are visible at once.
 */
object LayoutGeometry {

    /**
     * The Cockpit's **floating-app (left) tile** in normalized bounds: `[0,0,split,1]`,
     * mirrored to `[1-split,0,1,1]` when [swapped]. The single split→geometry primitive.
     */
    fun appTile(split: Float, swapped: Boolean): NormalizedBounds {
        val f = split.coerceIn(LayoutPreset.MIN_SPLIT, LayoutPreset.MAX_SPLIT)
        return if (swapped) NormalizedBounds(1f - f, 0f, 1f, 1f) else NormalizedBounds(0f, 0f, f, 1f)
    }

    /**
     * The panel's pixel bounds for an anchored layout: the strip the map does **not** cover.
     * Null when the map is not flush to an edge — then the panel has no complement to take and
     * the caller keeps it fullscreen.
     */
    fun panelComplement(mapBounds: NormalizedBounds, area: Rect): Rect? {
        val panel = when {
            mapBounds.left <= EDGE && mapBounds.right < 1f - EDGE ->
                NormalizedBounds(mapBounds.right, 0f, 1f, 1f)   // map left  → panel right
            mapBounds.right >= 1f - EDGE && mapBounds.left > EDGE ->
                NormalizedBounds(0f, 0f, mapBounds.left, 1f)    // map right → panel left
            else -> return null
        }
        return panel.toPixels(area)
    }

    /**
     * The pixel bounds the Cockpit panel window should occupy: the complement tile beside the
     * floating app, or [full] when the app is hidden or the map is not flush to an edge.
     */
    fun panelBounds(split: Float, swapped: Boolean, hidden: Boolean, area: Rect, full: Rect): Rect =
        if (hidden) full else panelComplement(appTile(split, swapped), area) ?: full

    /** The pixel bounds of the Cockpit's floating-app tile. Complement of [panelBounds]. */
    fun appBounds(split: Float, swapped: Boolean, area: Rect): Rect =
        appTile(split, swapped).toPixels(area)

    /** How close to an edge still counts as flush against it. */
    private const val EDGE = 0.01f
}
