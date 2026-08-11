package io.github.miklergm.witscompanion.layout

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists presets and user preferences.
 *
 * Custom presets are stored as JSON in SharedPreferences; the built-in defaults are
 * always available and are merged with any user additions.
 */
class LayoutRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ presets

    /**
     * Built-in presets with the user's per-preset tweaks applied (swapped sides and
     * adjusted split), followed by any fully custom presets.
     */
    fun allPresets(): List<LayoutPreset> =
        DefaultPresets.all().map { applyTweaks(it) } + customPresets()

    /** Applies the single user geometry to a preset. */
    private fun applyTweaks(preset: LayoutPreset): LayoutPreset =
        preset.withGeometry(split, swapped)

    /**
     * The one split ratio, shared by every layout.
     *
     * Geometry used to be stored per preset id, which meant the same proportion had to be
     * set again for each combination of apps. One control now drives them all: pick the
     * ratio once, then apply whichever apps you want with it.
     */
    var split: Float
        get() = prefs.getFloat(KEY_SPLIT, LayoutPreset.DEFAULT_SPLIT)
            .coerceIn(LayoutPreset.MIN_SPLIT, LayoutPreset.MAX_SPLIT)
        set(v) = prefs.edit()
            .putFloat(KEY_SPLIT, v.coerceIn(LayoutPreset.MIN_SPLIT, LayoutPreset.MAX_SPLIT))
            .apply()

    /** Whether the primary app sits on the right instead of the left. */
    var swapped: Boolean
        get() = prefs.getBoolean(KEY_SWAPPED, false)
        set(v) = prefs.edit().putBoolean(KEY_SWAPPED, v).apply()

    fun preset(id: String): LayoutPreset? = allPresets().firstOrNull { it.id == id }

    fun customPresets(): List<LayoutPreset> {
        val raw = prefs.getString(KEY_CUSTOM_PRESETS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { LayoutPreset.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveCustomPreset(preset: LayoutPreset) {
        val existing = customPresets().filterNot { it.id == preset.id }
        writeCustom(existing + preset)
    }

    fun deleteCustomPreset(id: String) {
        writeCustom(customPresets().filterNot { it.id == id })
    }

    private fun writeCustom(list: List<LayoutPreset>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }
        prefs.edit().putString(KEY_CUSTOM_PRESETS, arr.toString()).apply()
    }

    // ------------------------------------------------------------- last applied

    var lastAppliedPresetId: String?
        get() = prefs.getString(KEY_LAST_PRESET, null)
        set(value) = prefs.edit().putString(KEY_LAST_PRESET, value).apply()

    fun lastAppliedPreset(): LayoutPreset? = lastAppliedPresetId?.let { preset(it) }

    /**
     * What the Cockpit's left tile shows — the single source of truth for the floating app / the
     * hidden state / the config overlay / the default map. **One setter for every transition**, so
     * the old partial-write bugs cannot recur: an autostart panel coming up still "hidden" from a
     * previous session, a package left dangling behind the config. See [CockpitLeft] and
     * docs/window-management.md § 6.5.
     *
     * Persistence, preserved from the earlier three-flag design:
     *  - [CockpitLeft.App] / [CockpitLeft.Hidden] are **persisted** — the user's last real choice
     *    is restored on the next start (and survives a day/night activity recreation).
     *  - [CockpitLeft.Config] is an in-memory **overlay**: never persisted (so the gear does not
     *    come up lit after a restart) and it does not erase the content underneath, so dismissing
     *    the config — or a restart — restores the app/hidden/default that was there before.
     *  - [CockpitLeft.Default] clears the persisted content (map fallback).
     */
    var cockpitLeft: CockpitLeft
        get() = if (configOverlay) CockpitLeft.Config else persistedLeft()
        set(value) {
            configOverlay = value is CockpitLeft.Config
            if (value !is CockpitLeft.Config) persistLeft(value)
        }

    /** In-memory half of [cockpitLeft]: is the config overlaid on the left tile right now? */
    @Volatile
    private var configOverlay: Boolean = false

    /** The persisted (App / Hidden / Default) content of the left tile, ignoring the config overlay. */
    private fun persistedLeft(): CockpitLeft = when {
        prefs.getBoolean(KEY_COCKPIT_HIDDEN, false) -> CockpitLeft.Hidden
        else -> prefs.getString(KEY_COCKPIT_FLOAT, null)?.let { CockpitLeft.App(it) } ?: CockpitLeft.Default
    }

    private fun persistLeft(value: CockpitLeft) = prefs.edit().apply {
        when (value) {
            is CockpitLeft.App -> { putString(KEY_COCKPIT_FLOAT, value.packageName); putBoolean(KEY_COCKPIT_HIDDEN, false) }
            CockpitLeft.Hidden -> { remove(KEY_COCKPIT_FLOAT); putBoolean(KEY_COCKPIT_HIDDEN, true) }
            CockpitLeft.Default -> { remove(KEY_COCKPIT_FLOAT); putBoolean(KEY_COCKPIT_HIDDEN, false) }
            CockpitLeft.Config -> Unit // never persisted; handled by the setter
        }
    }.apply()

    // ------------------------------------------------------------- preferences
    // All automatic behaviours default to OFF. The user opts in explicitly.

    var restoreOnResume: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ON_RESUME, false)
        set(v) = prefs.edit().putBoolean(KEY_RESTORE_ON_RESUME, v).apply()

    var restoreOnAcc: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ON_ACC, false)
        set(v) = prefs.edit().putBoolean(KEY_RESTORE_ON_ACC, v).apply()

    var restoreOnAndroidSource: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ON_SOURCE, false)
        set(v) = prefs.edit().putBoolean(KEY_RESTORE_ON_SOURCE, v).apply()

    var restoreAfterReverse: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_AFTER_REVERSE, false)
        set(v) = prefs.edit().putBoolean(KEY_RESTORE_AFTER_REVERSE, v).apply()

    var restoreOnBoot: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ON_BOOT, false)
        set(v) = prefs.edit().putBoolean(KEY_RESTORE_ON_BOOT, v).apply()

    /** Bring the Mode B panel to the front on boot and ACC-on. The "soft launcher". */
    var autostartPanel: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART_PANEL, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSTART_PANEL, v).apply()

    /**
     * One user-facing switch for "bring the Cockpit up when the unit powers up" — folds the former
     * separate boot / ACC / autostart-panel flags. Boot and ACC-on are effectively the same event in
     * practice (turn the key → the unit powers → Android boots → ACC line goes high), so they share a
     * single control. Backed by [restoreOnBoot] + [restoreOnAcc] so existing installs migrate with no
     * data change.
     */
    var autostartOnPower: Boolean
        get() = restoreOnBoot || restoreOnAcc
        set(v) { restoreOnBoot = v; restoreOnAcc = v }

    /**
     * The hotspot state the user last chose, remembered so it can be restored after a
     * short stop turns it off. null = never set, so nothing to restore.
     */
    var hotspotDesiredOn: Boolean?
        get() = if (prefs.contains(KEY_HOTSPOT_DESIRED)) prefs.getBoolean(KEY_HOTSPOT_DESIRED, false) else null
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_HOTSPOT_DESIRED) else putBoolean(KEY_HOTSPOT_DESIRED, v)
        }.apply()

    /** Re-enable the hotspot on ACC-on/boot when it was on before. Opt-in. */
    var restoreHotspot: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_HOTSPOT, false)
        set(v) = prefs.edit().putBoolean(KEY_RESTORE_HOTSPOT, v).apply()

    var simulationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SIMULATION, false)
        set(v) = prefs.edit().putBoolean(KEY_SIMULATION, v).apply()

    /** Raw `wits_night_mode` recorded before our first write, for "undo". */
    var nightModeBackup: String?
        get() = prefs.getString(KEY_NIGHT_BACKUP, null)
        set(v) = prefs.edit().putString(KEY_NIGHT_BACKUP, v).apply()

    /**
     * Hard reset: erases every stored preset, tweak and preference, returning the app to
     * a first-install state. Layouts on screen are unaffected — use
     * [LayoutEngine.resetToVendorState] for those — this only clears what we persisted.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun exportPreferences(): JSONObject = JSONObject().apply {
        put(KEY_RESTORE_ON_RESUME, restoreOnResume)
        put(KEY_RESTORE_ON_ACC, restoreOnAcc)
        put(KEY_RESTORE_ON_SOURCE, restoreOnAndroidSource)
        put(KEY_RESTORE_AFTER_REVERSE, restoreAfterReverse)
        put(KEY_RESTORE_ON_BOOT, restoreOnBoot)
        put(KEY_SIMULATION, simulationEnabled)
        put(KEY_LAST_PRESET, lastAppliedPresetId ?: "none")
    }

    private companion object {
        const val PREFS = "wits_companion_layouts"
        const val KEY_CUSTOM_PRESETS = "custom_presets"
        const val KEY_LAST_PRESET = "last_preset"
        const val KEY_COCKPIT_FLOAT = "cockpit_floating_package"
        const val KEY_COCKPIT_HIDDEN = "cockpit_floating_hidden"
        const val KEY_RESTORE_ON_RESUME = "restore_on_resume"
        const val KEY_RESTORE_ON_ACC = "restore_on_acc"
        const val KEY_RESTORE_ON_SOURCE = "restore_on_android_source"
        const val KEY_RESTORE_AFTER_REVERSE = "restore_after_reverse"
        const val KEY_RESTORE_ON_BOOT = "restore_on_boot"
        const val KEY_AUTOSTART_PANEL = "autostart_panel"
        const val KEY_HOTSPOT_DESIRED = "hotspot_desired_on"
        const val KEY_RESTORE_HOTSPOT = "restore_hotspot"
        const val KEY_SIMULATION = "simulation_enabled"
        const val KEY_NIGHT_BACKUP = "night_mode_backup"
        const val KEY_SPLIT = "geometry_split"
        const val KEY_SWAPPED = "geometry_swapped"
    }
}
