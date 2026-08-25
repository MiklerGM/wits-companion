package io.github.miklergm.witscompanion.ui

import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.layout.CockpitLeft
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutPreset
import io.github.miklergm.witscompanion.layout.PresetKind
import io.github.miklergm.witscompanion.media.MediaCustomAction
import io.github.miklergm.witscompanion.media.MediaSnapshot
import io.github.miklergm.witscompanion.nav.NavigationSnapshot
import io.github.miklergm.witscompanion.wits.HotspotController
import io.github.miklergm.witscompanion.wits.WitsPackages

/**
 * Everything the Cockpit panel shows, as data.
 *
 * The panel used to work these out inline while rendering, which meant the decisions — which
 * app is floating and therefore which rail tile lights, whether a transport button is usable,
 * where the reserved strip goes — could only be exercised by running the Activity. They are
 * ordinary branching over repository state and deserve ordinary tests.
 *
 * Everything here is derived by pure functions in [CockpitState]; [CockpitViewModel] is only
 * the plumbing that feeds them and publishes the result.
 */
data class CockpitUiState(
    val statusText: String = "",
    val floatingPackage: String? = null,
    val railPackages: List<String> = emptyList(),
    val settingsTileSelected: Boolean = false,
    val media: MediaPanel = MediaPanel(),
    val navigation: NavPanel = NavPanel(),
    val hotspot: HotspotPanel = HotspotPanel(),
    val brightnessPercent: Int? = null,
    val reservation: Reservation? = null,
) {
    /** Which side the floating app occupies, and how much width the panel must leave it. */
    data class Reservation(val onRight: Boolean, val fraction: Float)

    data class MediaPanel(
        val available: Boolean = false,
        val title: String? = null,
        val subtitle: String? = null,
        val isPlaying: Boolean = false,
        val canPrevious: Boolean = false,
        val canPlayPause: Boolean = false,
        val canNext: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val positionUpdatedElapsedMs: Long = 0L,
        /** The player's add/remove-from-collection action, when it offers one. */
        val collection: CollectionAction? = null,
        /** The snapshot this was derived from, for the view-only concerns: art and tinting. */
        val raw: MediaSnapshot? = null,
    ) {
        val hasProgress: Boolean get() = durationMs > 0L

        /**
         * Position extrapolated to [now].
         *
         * `PlaybackState.position` is the position as of `lastPositionUpdateTime`, not as of
         * now, so the elapsed term is measured from that origin — stamping it with the
         * snapshot time made progress lag by however long ago the player last published.
         * Only advances while playing: a paused track's position is already current.
         */
        fun positionAt(now: Long): Long {
            if (!hasProgress) return 0L
            val elapsed = if (isPlaying) now - positionUpdatedElapsedMs else 0L
            return (positionMs + elapsed).coerceIn(0L, durationMs)
        }
    }

    /**
     * The next manoeuvre, shown above the media card while navigation is running.
     *
     * [visible] is false whenever there is nothing worth a row — no navigation, or navigation
     * whose notification carried no readable instruction. The panel renders nothing at all in
     * that case rather than an empty strip, so the media card keeps its position.
     */
    /**
     * The "save this track" action, as the player currently offers it.
     *
     * @param action  the id to send back — note it changes with state, see [CockpitState.collectionAction]
     * @param label   the player's own wording, e.g. "Add to collection"
     * @param saved   whether the track is already in the collection
     */
    data class CollectionAction(val action: String, val label: String, val saved: Boolean)

    data class NavPanel(
        val visible: Boolean = false,
        val instruction: String? = null,
        val distance: String? = null,
        val eta: String? = null,
        /** The navigator's own manoeuvre arrow, when the notification carried one. */
        val icon: android.graphics.drawable.Icon? = null,
    )

    data class HotspotPanel(
        val supported: Boolean = false,
        val state: HotspotController.State = HotspotController.State.UNKNOWN,
    ) {
        val busy: Boolean get() = state == HotspotController.State.TURNING_ON ||
            state == HotspotController.State.TURNING_OFF
        val on: Boolean get() = state == HotspotController.State.ON
    }
}

/**
 * The Cockpit's display decisions, as pure functions.
 *
 * No Android types beyond the plain data the repositories already expose, no clock reads, no
 * view references — so each of these is a unit test rather than an on-car observation.
 */
object CockpitState {

    /**
     * Which app currently floats over the panel, and therefore which rail tile lights.
     *
     * Prefers the package remembered directly: that survives an activity recreation and
     * resolves the switcher's on-the-fly `anchored_<pkg>` presets, which are never stored and
     * so cannot be found by looking through the saved ones. Falls back to the last applied
     * anchored preset, then to the default map preset.
     *
     * `Hidden` and `Config` deliberately return null — no app tile should light when the app
     * is hidden or the configuration has taken the slot.
     */
    fun floatingPackage(
        cockpitLeft: CockpitLeft,
        lastAppliedPreset: LayoutPreset?,
        defaultAnchored: LayoutPreset?,
    ): String? = when (cockpitLeft) {
        is CockpitLeft.App -> cockpitLeft.packageName
        CockpitLeft.Hidden, CockpitLeft.Config -> null
        CockpitLeft.Default -> anchoredForeignPackage(lastAppliedPreset)
            ?: anchoredForeignPackage(defaultAnchored)
    }

    private fun anchoredForeignPackage(preset: LayoutPreset?): String? = preset
        ?.takeIf { it.kind == PresetKind.ANCHORED }
        ?.windows?.firstOrNull { it.packageName != WitsPackages.SELF }
        ?.packageName

    /**
     * The rail's app tiles: the vendor's own preferred slots first, then the usual suspects,
     * filtered to what is actually installed and capped at four so the rail stays reachable.
     */
    fun railPackages(
        vendorNavigation: String?,
        vendorMusic: String?,
        vendorVideo: String?,
        isLaunchable: (String) -> Boolean,
        limit: Int = RAIL_LIMIT,
    ): List<String> = listOfNotNull(
        vendorNavigation, vendorMusic, vendorVideo,
        WitsPackages.MAPS, WitsPackages.CHROME, WitsPackages.SPOTIFY,
    ).distinct().filter(isLaunchable).take(limit)

    /**
     * Where the floating app sits, so the panel leaves that strip empty on the correct side.
     *
     * Only reserved when the panel actually fills the display: a narrow tile beside the app
     * reserves nothing, because the app is not underneath it. This is what paints the black
     * strip in the hidden state, where the panel is full-width but keeps its content's width.
     */
    fun reservation(
        fillsDisplay: Boolean,
        split: Float,
        swapped: Boolean,
    ): CockpitUiState.Reservation? =
        if (!fillsDisplay) null else CockpitUiState.Reservation(onRight = swapped, fraction = split)

    fun media(snapshot: MediaSnapshot?): CockpitUiState.MediaPanel {
        if (snapshot == null) return CockpitUiState.MediaPanel()
        if (!snapshot.available) return CockpitUiState.MediaPanel(
            canPlayPause = true,   // no session: the media-key fallback is the point
            raw = snapshot,
        )
        return CockpitUiState.MediaPanel(
            available = true,
            title = snapshot.title,
            subtitle = listOfNotNull(snapshot.artist, snapshot.album)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .takeIf { it.isNotBlank() },
            isPlaying = snapshot.isPlaying,
            canPrevious = snapshot.canSkipPrevious,
            // With NO live session the player reports no actions at all, and play must still be
            // tappable — that is exactly the case it exists for, dispatching a media key that
            // wakes the player's playback service. A live session that advertises nothing is a
            // different thing and stays disabled. Left enabled in both cases the button was
            // simply dead until the user opened the player themselves (`[RUNTIME]` 2026-08-17).
            canPlayPause = snapshot.canPlay || snapshot.canPause || !snapshot.available,
            canNext = snapshot.canSkipNext,
            durationMs = snapshot.durationMs,
            positionMs = snapshot.positionMs,
            positionUpdatedElapsedMs = snapshot.positionUpdatedElapsedMs,
            collection = collectionAction(snapshot.customActions),
            raw = snapshot,
        )
    }

    /**
     * The one-line vehicle status above the media card.
     *
     * Uses [CarState.reverseActive] — the *display* accessor — on purpose: this line reports
     * what is known, and the last reading is the honest thing to show even when it is too old
     * to authorise anything. The guards use the control-grade accessor instead.
     */
    fun statusText(state: CarState): String = buildString {
        append(state.sourceName)
        append("   ACC ").append(state.acc.display())
        if (state.reverseActive == true) append("   REVERSE")
    }

    /**
     * The navigation row.
     *
     * Shown only when an instruction actually arrived: a navigator that is running but whose
     * notification we could not parse gives an empty row, which is worse than none — it takes
     * space and tells the driver nothing. Distance and ETA are passed through exactly as the
     * app wrote them, never reformatted; converting "300 m" into our own units is how a
     * display starts disagreeing with the map beside it.
     */
    fun navigation(snapshot: NavigationSnapshot?): CockpitUiState.NavPanel {
        if (snapshot == null || !snapshot.hasInstruction) return CockpitUiState.NavPanel()
        return CockpitUiState.NavPanel(
            visible = true,
            instruction = snapshot.instruction,
            distance = snapshot.distance,
            eta = snapshot.eta,
            icon = snapshot.icon,
        )
    }

    /**
     * Finds the player's add/remove-from-collection action among its custom actions.
     *
     * Spotify names custom actions after the **icon** it wants drawn, not after what they do
     * `[RUNTIME]` 2026-08-25: the observed set was `TURN_SHUFFLE_ON`, `CHECK_FILL`,
     * `START_RADIO`, `TURN_REPEAT_ALL_ON`, where `CHECK_FILL` was "Remove from collection".
     *
     * Two consequences, and they drive this whole function:
     *
     *  - **The id changes with state.** A filled check means the track is saved; an unsaved one
     *    advertises a different id. Matching a single constant would work on saved tracks and
     *    quietly fail on the rest — the worst kind of failure for a button, because it looks
     *    fine. So an id set is matched, and the `_FILL`/`ACTIVE` suffix is read as the state.
     *  - **The name cannot be matched on.** It is user-facing text and therefore localised;
     *    "Remove from collection" is only English. It is good enough to *display*, which is
     *    what it is used for.
     *
     * Anything unrecognised yields null and no button, rather than a guess that might fire
     * "Start radio". The Debug screen lists every action, so an unmatched id is a one-look fix.
     */
    fun collectionAction(actions: List<MediaCustomAction>): CockpitUiState.CollectionAction? {
        val match = actions.firstOrNull { it.action.uppercase() in COLLECTION_ACTION_IDS } ?: return null
        val id = match.action.uppercase()
        val saved = id in SAVED_IDS || SAVED_SUFFIXES.any { id.endsWith(it) }
        return CockpitUiState.CollectionAction(match.action, match.name, saved)
    }

    /**
     * Ids Spotify uses for the collection toggle.
     *
     * The two halves of the same button do not follow one scheme, which is the whole reason
     * this is a list rather than a rule `[RUNTIME]` 2026-08-25:
     *
     * ```
     * ADD_TO      -> "Add to collection"        (track not saved)
     * CHECK_FILL  -> "Remove from collection"   (track saved)
     * ```
     *
     * One is semantic, the other is the icon's name. Both were read off the device; the rest
     * are the neighbouring icon constants from the APK `[CODE]`, kept as plausible variants
     * for other builds. An id that is not here yields no button rather than a wrong one, and
     * the Debug screen lists whatever actually arrived — which is how `ADD_TO` was found after
     * a first guess of icon names alone missed it entirely.
     */
    private val COLLECTION_ACTION_IDS = setOf(
        "ADD_TO", "REMOVE_FROM",
        "CHECK", "CHECK_FILL", "CHECK_ALT", "CHECK_ALT_FILL",
        "PLUS", "PLUS_ALT", "PLUS_2PX",
        "HEART", "HEART_FILL", "HEART_ACTIVE",
        "ADD_TO_PLAYLIST",
    )

    /** A filled or active icon is the player saying the track is already in the collection. */
    private val SAVED_SUFFIXES = listOf("_FILL", "_ACTIVE")

    /** Ids that mean "already saved" without carrying a suffix to say so. */
    private val SAVED_IDS = setOf("REMOVE_FROM")

    fun hotspot(supported: Boolean, state: HotspotController.State) =
        CockpitUiState.HotspotPanel(supported = supported, state = state)

    /**
     * Whether an automatic re-assert should be attempted for this car state.
     *
     * Mirrors what the panel may act on without the driver asking: never over the reverse
     * camera, and never when reverse is merely unknown — the guard makes the final call, but
     * the panel should not even offer it.
     */
    fun automationPermitted(state: CarState): Boolean = state.reverseActive == false

    const val RAIL_LIMIT = 4
}
