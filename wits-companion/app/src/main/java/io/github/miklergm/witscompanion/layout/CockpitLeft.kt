package io.github.miklergm.witscompanion.layout

/**
 * What the Cockpit's **left tile** currently shows — the single source of truth that replaces
 * the old three-flag combo (`cockpitFloatingPackage` + `cockpitFloatingHidden` +
 * `cockpitLeftIsConfig`).
 *
 * Modelling it as one closed set of states makes the illegal combinations unrepresentable — an
 * app *and* hidden, a package left dangling behind the config, a partial write that forgot to
 * clear a companion flag. Every transition is now a single total assignment through
 * [LayoutRepository.cockpitLeft], so no call site can leave the state half-updated.
 *
 * See docs/window-management.md § 6.5 for the transition diagram.
 */
sealed interface CockpitLeft {

    /**
     * A foreign app floats over the panel — the map, or an app picked from the switcher rail.
     *
     * The package is remembered directly rather than via [LayoutRepository.lastAppliedPreset]:
     * the switcher's on-the-fly presets have ids like `anchored_<pkg>` that are not in
     * [LayoutRepository.allPresets], so the last-applied id cannot always be resolved back to a
     * package. Persisted, so a restart restores the same tile highlighted.
     */
    data class App(val packageName: String) : CockpitLeft

    /**
     * The user dismissed the app (tapped the active switcher tile): the panel fills the display,
     * nothing floats and no tile is lit. Distinct from [Default] — a deliberate empty state, not
     * "unknown, fall back to the map". Persisted.
     */
    data object Hidden : CockpitLeft

    /**
     * The config ([io.github.miklergm.witscompanion.ui.MainActivity]) occupies the left tile
     * (the Settings gear was tapped). **Transient — never persisted**: the config screen is not
     * re-opened on a fresh start, so the gear must not come up lit, and this state must not erase
     * the app/hidden/default underneath it. Mutually exclusive with a lit app tile.
     */
    data object Config : CockpitLeft

    /** No explicit choice yet — fall back to the default map anchored preset. */
    data object Default : CockpitLeft
}
