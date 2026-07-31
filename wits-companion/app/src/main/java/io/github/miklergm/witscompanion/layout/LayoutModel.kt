package io.github.miklergm.witscompanion.layout

import android.graphics.Rect
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolution-independent bounds, 0.0 .. 1.0 of the usable display area.
 *
 * Stored normalized so a preset survives a display/insets change and can be
 * shared between devices.
 */
data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isValid: Boolean
        get() = left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
                left < right && top < bottom

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * Converts to absolute pixels inside [area].
     *
     * @param area the usable region (display minus insets), already in pixels
     */
    fun toPixels(area: Rect): Rect {
        val w = area.width()
        val h = area.height()
        return Rect(
            area.left + Math.round(left * w),
            area.top + Math.round(top * h),
            area.left + Math.round(right * w),
            area.top + Math.round(bottom * h),
        )
    }

    fun overlaps(other: NormalizedBounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left.toDouble()); put("top", top.toDouble())
        put("right", right.toDouble()); put("bottom", bottom.toDouble())
    }

    companion object {
        val FULL = NormalizedBounds(0f, 0f, 1f, 1f)

        fun fromJson(o: JSONObject) = NormalizedBounds(
            o.optDouble("left", 0.0).toFloat(),
            o.optDouble("top", 0.0).toFloat(),
            o.optDouble("right", 1.0).toFloat(),
            o.optDouble("bottom", 1.0).toFloat(),
        )
    }
}

/**
 * One tile of a layout.
 *
 * @param launchIntentUri optional deep link, fired before CHANGE_WINDOW when the task
 *        does not exist yet. Needed because the vendor hook can only start a package's
 *        MAIN activity (docs/window-management.md §4.2).
 * @param focusOrder windows are applied in ascending order, so the highest value is
 *        applied last and receives focus.
 */
data class LayoutWindow(
    val packageName: String,
    val bounds: NormalizedBounds,
    val windowMode: Int = WitsWindowMode.FREEFORM,
    val launchIntentUri: String? = null,
    val focusOrder: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("bounds", bounds.toJson())
        put("windowMode", windowMode)
        launchIntentUri?.let { put("launchIntentUri", it) }
        put("focusOrder", focusOrder)
    }

    companion object {
        fun fromJson(o: JSONObject) = LayoutWindow(
            packageName = o.getString("packageName"),
            bounds = NormalizedBounds.fromJson(o.getJSONObject("bounds")),
            windowMode = o.optInt("windowMode", WitsWindowMode.FREEFORM),
            launchIntentUri = o.optString("launchIntentUri", "").takeIf { it.isNotEmpty() },
            focusOrder = o.optInt("focusOrder", 0),
        )
    }
}

/**
 * How a preset occupies the screen.
 *
 * The vendor hook has no "close window" primitive and a freeform window always floats
 * above fullscreen tasks, so the two arrangements need different transitions — see
 * [LayoutEngine] and docs/window-management.md.
 */
enum class PresetKind {
    /** Foreign apps tile the screen; the companion is only an orchestrator. */
    TILED,

    /**
     * The companion sits fullscreen as an anchor and exactly one foreign window floats
     * above it, leaving the rest of the companion visible. Everything else is surfaced
     * through APIs (MediaSession, properties) rather than as extra windows.
     */
    ANCHORED,
}

data class LayoutPreset(
    val id: String,
    val title: String,
    val windows: List<LayoutWindow>,
    val experimental: Boolean = false,
    val kind: PresetKind = PresetKind.TILED,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("experimental", experimental)
        put("kind", kind.name)
        put("windows", JSONArray().also { arr -> windows.forEach { arr.put(it.toJson()) } })
    }

    /**
     * Mirrors every tile horizontally, so a left/right pair swaps sides.
     * `[0.00,0.65]` and `[0.65,1.00]` become `[0.35,1.00]` and `[0.00,0.35]`.
     */
    fun mirrored(newId: String = id + "_mirrored", newTitle: String = "$title (swapped)"): LayoutPreset =
        copy(
            id = newId,
            title = newTitle,
            windows = windows.map { w ->
                w.copy(
                    bounds = w.bounds.copy(
                        left = 1f - w.bounds.right,
                        right = 1f - w.bounds.left,
                    )
                )
            },
        )

    /**
     * Re-splits a two-tile preset at [leftFraction], keeping order and focus.
     * Returns the preset unchanged when it does not have exactly two tiles.
     */
    fun withSplit(leftFraction: Float, newId: String = id, newTitle: String = title): LayoutPreset {
        if (windows.size != 2) return this
        val f = leftFraction.coerceIn(MIN_SPLIT, MAX_SPLIT)
        val sorted = windows.sortedBy { it.bounds.left }
        return copy(
            id = newId,
            title = newTitle,
            windows = listOf(
                sorted[0].copy(bounds = sorted[0].bounds.copy(left = 0f, right = f)),
                sorted[1].copy(bounds = sorted[1].bounds.copy(left = f, right = 1f)),
            ),
        )
    }

    /** Left-hand fraction of a two-tile preset, or null if it is not a simple split. */
    fun splitFraction(): Float? {
        if (windows.size != 2) return null
        val sorted = windows.sortedBy { it.bounds.left }
        val a = sorted[0].bounds
        val b = sorted[1].bounds
        if (a.left != 0f || b.right != 1f) return null
        if (kotlin.math.abs(a.right - b.left) > 0.001f) return null
        return a.right
    }

    companion object {
        const val MIN_SPLIT = 0.25f
        const val MAX_SPLIT = 0.80f

        fun fromJson(o: JSONObject): LayoutPreset {
            val arr = o.getJSONArray("windows")
            return LayoutPreset(
                id = o.getString("id"),
                title = o.getString("title"),
                experimental = o.optBoolean("experimental", false),
                kind = runCatching { PresetKind.valueOf(o.optString("kind")) }
                    .getOrDefault(PresetKind.TILED),
                windows = (0 until arr.length()).map { LayoutWindow.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}

/** A problem found before anything is sent to the firmware. */
data class LayoutIssue(val severity: Severity, val message: String) {
    enum class Severity { ERROR, WARNING }
}

/**
 * Validates a preset against the constraints of the vendor hook.
 *
 * Hard rules (docs/window-management.md §4):
 *  - bounds must be normalized and non-degenerate,
 *  - a package may appear at most once (the hook reuses the first running task),
 *  - the package list must not be empty.
 *
 * Soft rules produce warnings: overlapping tiles, very small tiles, experimental presets.
 */
object LayoutValidator {

    private const val MIN_EDGE = 0.10f

    fun validate(preset: LayoutPreset): List<LayoutIssue> {
        val issues = mutableListOf<LayoutIssue>()

        if (preset.windows.isEmpty()) {
            issues += LayoutIssue(LayoutIssue.Severity.ERROR, "preset has no windows")
            return issues
        }

        val seen = mutableSetOf<String>()
        preset.windows.forEach { w ->
            if (!seen.add(w.packageName)) {
                issues += LayoutIssue(
                    LayoutIssue.Severity.ERROR,
                    "duplicate package ${w.packageName}: only one window per package is possible",
                )
            }
            if (!w.bounds.isValid) {
                issues += LayoutIssue(
                    LayoutIssue.Severity.ERROR,
                    "invalid bounds for ${w.packageName}: ${w.bounds}",
                )
            } else {
                if (w.bounds.width < MIN_EDGE || w.bounds.height < MIN_EDGE) {
                    issues += LayoutIssue(
                        LayoutIssue.Severity.WARNING,
                        "${w.packageName} tile is very small (${pct(w.bounds.width)}x${pct(w.bounds.height)})",
                    )
                }
            }
        }

        preset.windows.forEachIndexed { i, a ->
            preset.windows.drop(i + 1).forEach { b ->
                if (a.bounds.isValid && b.bounds.isValid && a.bounds.overlaps(b.bounds)) {
                    issues += LayoutIssue(
                        LayoutIssue.Severity.WARNING,
                        "${a.packageName} and ${b.packageName} overlap",
                    )
                }
            }
        }

        if (preset.kind == PresetKind.ANCHORED) {
            if (preset.windows.size != 1) {
                issues += LayoutIssue(
                    LayoutIssue.Severity.ERROR,
                    "an anchored preset must have exactly one foreign window, has ${preset.windows.size}",
                )
            }
            if (preset.windows.any { it.packageName == WitsPackages.SELF }) {
                issues += LayoutIssue(
                    LayoutIssue.Severity.ERROR,
                    "the companion is the anchor and must not also be listed as a tile",
                )
            }
        }

        if (preset.experimental) {
            issues += LayoutIssue(
                LayoutIssue.Severity.WARNING,
                "experimental preset: behaviour with 3+ windows is unverified",
            )
        }

        return issues
    }

    fun hasErrors(issues: List<LayoutIssue>): Boolean =
        issues.any { it.severity == LayoutIssue.Severity.ERROR }

    private fun pct(v: Float) = "${Math.round(v * 100)}%"
}

/** The presets shipped with the MVP. */
object DefaultPresets {

    const val ID_MAPS_SPOTIFY = "maps65_spotify35"
    const val ID_MAPS_CHROME = "maps65_chrome35"
    const val ID_MAPS_COMPANION = "maps65_companion35"
    const val ID_MAPS_ANCHORED = "maps65_anchored"
    const val ID_MAPS_FULL = "maps_full"
    const val ID_SPOTIFY_FULL = "spotify_full"
    const val ID_THREE_PANEL = "three_panel"

    fun all(): List<LayoutPreset> = listOf(
        LayoutPreset(
            id = ID_MAPS_SPOTIFY,
            title = "Maps 65 / Spotify 35",
            windows = listOf(
                LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0f, 0f, 0.65f, 1f), focusOrder = 0),
                LayoutWindow(WitsPackages.SPOTIFY, NormalizedBounds(0.65f, 0f, 1f, 1f), focusOrder = 1),
            ),
        ),
        LayoutPreset(
            id = ID_MAPS_CHROME,
            title = "Maps 65 / Chrome 35",
            windows = listOf(
                LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0f, 0f, 0.65f, 1f), focusOrder = 0),
                LayoutWindow(WitsPackages.CHROME, NormalizedBounds(0.65f, 0f, 1f, 1f), focusOrder = 1),
            ),
        ),
        LayoutPreset(
            id = ID_MAPS_COMPANION,
            title = "Maps 65 / Companion 35",
            windows = listOf(
                LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0f, 0f, 0.65f, 1f), focusOrder = 0),
                LayoutWindow(WitsPackages.SELF, NormalizedBounds(0.65f, 0f, 1f, 1f), focusOrder = 1),
            ),
        ),
        LayoutPreset(
            id = ID_MAPS_ANCHORED,
            title = "Maps + companion panel",
            kind = PresetKind.ANCHORED,
            windows = listOf(
                // Only one foreign window. The companion stays fullscreen underneath and
                // shows through on the right; Spotify is driven via MediaSession instead
                // of getting a window of its own.
                LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0f, 0f, 0.65f, 1f), focusOrder = 0),
            ),
        ),
        LayoutPreset(
            id = ID_MAPS_FULL,
            title = "Maps fullscreen",
            windows = listOf(LayoutWindow(WitsPackages.MAPS, NormalizedBounds.FULL)),
        ),
        LayoutPreset(
            id = ID_SPOTIFY_FULL,
            title = "Spotify fullscreen",
            windows = listOf(LayoutWindow(WitsPackages.SPOTIFY, NormalizedBounds.FULL)),
        ),
        LayoutPreset(
            id = ID_THREE_PANEL,
            title = "Three panel (experimental)",
            experimental = true,
            windows = listOf(
                LayoutWindow(WitsPackages.MAPS, NormalizedBounds(0f, 0f, 0.65f, 1f), focusOrder = 0),
                LayoutWindow(WitsPackages.SPOTIFY, NormalizedBounds(0.65f, 0f, 1f, 0.5f), focusOrder = 1),
                LayoutWindow(WitsPackages.SELF, NormalizedBounds(0.65f, 0.5f, 1f, 1f), focusOrder = 2),
            ),
        ),
    )
}
