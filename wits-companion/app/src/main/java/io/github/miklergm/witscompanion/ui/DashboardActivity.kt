package io.github.miklergm.witscompanion.ui

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.CarStateRepository
import io.github.miklergm.witscompanion.layout.CockpitLeft
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutEngine
import io.github.miklergm.witscompanion.layout.PresetKind
import io.github.miklergm.witscompanion.media.MediaSessionRepository
import io.github.miklergm.witscompanion.media.MediaSnapshot
import io.github.miklergm.witscompanion.safety.Trigger
import io.github.miklergm.witscompanion.wits.BrightnessController
import io.github.miklergm.witscompanion.wits.HotspotController
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import io.github.miklergm.witscompanion.wits.statusBarHeightPx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Mode B anchor: a fullscreen panel the companion keeps behind a single floating
 * foreign window (the map).
 *
 * Deliberately **not** a tab in [MainActivity]. That activity is the configuration and
 * diagnostics tool — tabs, monospace dumps, export buttons. This is the screen looked at
 * while driving, so it carries only what is useful in motion and leaves the region the
 * map occupies empty.
 *
 * Observation only, with one exception: media transport. Play/pause/next/previous go
 * through [MediaSessionRepository], i.e. the standard Android MediaSession API aimed at
 * the player app itself. Nothing here writes a property, a setting or an MCU value, and
 * nothing switches the source.
 *
 * Volume is **displayed, never set** — the vendor AudioService ignores volume changes
 * from any caller other than `com.wits.pms` (docs/audio-volume.md), so a control here
 * would be a button that silently does nothing.
 *
 * PDC and door state are intentionally absent: they are already on the instrument cluster
 * and the HUD, and reversing switches the head unit to the OEM source anyway.
 */
/**
 * Extends `androidx.activity.ComponentActivity` rather than plain `Activity` for the
 * ViewModelStore and `lifecycleScope` — deliberately not `AppCompatActivity`, which adds a
 * view-inflation layer this screen does not use (every view here is built in code) and which
 * would put more between us and the decor. The panel's window handling is the most
 * on-car-tuned part of the app, so the base class change is kept as small as it can be.
 */
class DashboardActivity : ComponentActivity() {

    private lateinit var app: WitsCompanionApp

    private val model: CockpitViewModel by lazy {
        ViewModelProvider(this, CockpitViewModel.Factory(app))[CockpitViewModel::class.java]
    }

    private lateinit var clockView: TextView
    private lateinit var stateView: TextView
    private lateinit var trackView: TextView
    private lateinit var artistView: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var mediaCard: LinearLayout
    private lateinit var navCard: LinearLayout
    private lateinit var navDistanceView: TextView
    private lateinit var navInstructionView: TextView
    private var hotspotTile: LinearLayout? = null
    private var hotspotText: TextView? = null
    private var hotspotIcon: TextView? = null
    private var brightnessTile: LinearLayout? = null
    private var brightnessLabel: TextView? = null
    private lateinit var artView: android.widget.ImageView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var progressLabel: TextView

    /** Floating-app switcher tiles, kept so the highlight can move on selection. */
    private val switcherTiles = mutableListOf<Pair<String, LinearLayout>>()

    /** The Settings gear tile, lit like a switcher entry when the config occupies the left tile. */
    private var settingsTile: LinearLayout? = null

    @Volatile
    private var latestMedia: MediaSnapshot? = null

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = CLOCK.format(Date())
            refreshProgress()
            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as WitsCompanionApp
        // Take the insets over so the decor does not *also* pad for the top strip: buildRoot()
        // floors the top itself when the panel fills the display, and a second automatic inset
        // would double it (the "everything slid too far down" report in the full-screen state).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildRoot())

        // One subscription for the panel's whole lifetime. repeatOnLifecycle already suspends
        // the collection between STOPPED and STARTED, so this belongs in onCreate — launching
        // it from onStart would add a fresh collector on every start, and lifecycleScope only
        // cancels at DESTROY, so they would accumulate. Lint's RepeatOnLifecycleWrongUsage
        // caught exactly that.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    // ------------------------------------------------------------------ layout

    /**
     * The panel occupies the part of the display the floating window does not cover.
     *
     * The gap is taken from the currently selected ANCHORED preset, so changing the
     * preset's split moves the panel with it instead of leaving content hidden under the
     * map. Falls back to the full width when no anchored preset is active.
     */
    private fun buildRoot(): View {
        val reservation = reservation()
        val reserved = reservation?.fraction ?: 0f

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.background)
        }

        // Floor the top with the status-bar height only when the panel fills the display AND the
        // strip is actually there — i.e. the autostart full-screen state (bar visible). In the
        // *hidden* state we hide the strip ([applyImmersive]) so the content uses full height (no
        // gap), and in tile mode the window already sits below the bar (usableArea floors it).
        if (fillsDisplay() && app.layoutRepository.cockpitLeft !is CockpitLeft.Hidden) {
            row.setPadding(0, statusBarHeightPx(), 0, 0)
        }

        // Empty spacer where the map floats — on the same side as the map. Nothing may be
        // drawn there. When the map is on the right the spacer follows the panel.
        fun addSpacer() {
            if (reserved > 0f) row.addView(View(this), LinearLayout.LayoutParams(0, MATCH, reserved))
        }
        if (reservation?.side == MapSide.LEFT) addSpacer()

        // Two columns: a main column (media + quick toggles) and a narrow right-hand rail
        // (Settings on top, the floating-app switcher, Exit pinned to the bottom). Moving the
        // switcher and the two actions into the rail frees the media block's height, so the
        // panel no longer has to scroll under the vendor top bar.
        // Top padding is small: the row already clears the vendor strip (tile placed below it,
        // or floored when full-screen), so the panel content lines up with the edge-to-edge app
        // on the left instead of sitting ~20 px lower (its own top padding was the visible gap).
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad(16), pad(4), pad(12), pad(14))
        }
        row.addView(panel, LinearLayout.LayoutParams(0, MATCH, 1f - reserved))
        if (reservation?.side == MapSide.RIGHT) addSpacer()

        // Clock/ACC are not shown (the vendor top strip already has a clock); kept as detached
        // views so the per-second tick and onCarState stay valid without null checks.
        clockView = TextView(this)
        stateView = TextView(this)

        // The main column: media card and the quick toggles, taking the width the rail leaves.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
        }

        // The next manoeuvre, above the media card and only while navigating. Deliberately
        // ABOVE: while driving it is the more urgent of the two, and putting it under the
        // transport row would make it move whenever the media card grows or shrinks.
        navCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(16), pad(12), pad(16), pad(12))
            visibility = View.GONE          // nothing to say until an instruction arrives
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = pad(14).toFloat()
                setColor(Color.parseColor("#1F2A24"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = pad(8) }
        }
        // Distance leads: it is the number the driver is actually timing the manoeuvre against,
        // and it is short enough to read at a glance.
        navDistanceView = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#8FD9B6"))
            setPadding(0, 0, pad(14), 0)
        }
        navInstructionView = TextView(this).apply {
            textSize = 15f
            setTextColor(palette.foreground)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        navCard.addView(navDistanceView)
        navCard.addView(navInstructionView)
        content.addView(navCard)

        // Media as a single rounded card, tinted by what is playing — the panel takes on
        // the album's colour (Mini AA's MediaPlayerCard in spirit). onMedia() fills in the
        // accent, the art and the transport state; here we only build the frame.
        mediaCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(16), pad(14), pad(16), pad(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(mediaCard)

        val nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Bigger, rounded album art. Kept visible with an app-icon placeholder when a
        // player is active but exposes no artwork, so the card never reads as empty.
        artView = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(pad(96), pad(96))
                .apply { rightMargin = pad(16) }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = pad(14).toFloat()
            }
            visibility = View.GONE
        }
        nowPlaying.addView(artView)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        trackView = TextView(this).apply {
            textSize = 22f; setTextColor(palette.foreground); maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
        }
        texts.addView(trackView)

        artistView = TextView(this).apply {
            textSize = 14f; setTextColor(palette.muted); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, pad(3), 0, 0)
        }
        texts.addView(artistView)
        nowPlaying.addView(texts)
        mediaCard.addView(nowPlaying)

        progressBar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = PROGRESS_MAX
            layoutParams = LinearLayout.LayoutParams(MATCH, pad(3)).apply { topMargin = pad(14) }
            visibility = View.INVISIBLE
        }
        mediaCard.addView(progressBar)

        progressLabel = TextView(this).apply {
            textSize = 11f; setTextColor(palette.muted); typeface = Typeface.MONOSPACE
            setPadding(0, pad(3), 0, pad(6))
        }
        mediaCard.addView(progressLabel)

        // Transport centred, with an emphasised (filled) play/pause between the skips.
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, pad(4), 0, 0)
        }
        prevButton = transportButton("⏮", emphasised = false) { app.mediaRepository.previous() }
        playPauseButton = transportButton("▶", emphasised = true) { app.mediaRepository.playPause() }
        nextButton = transportButton("⏭", emphasised = false) { app.mediaRepository.next() }
        transport.addView(prevButton)
        transport.addView(playPauseButton)
        transport.addView(nextButton)
        mediaCard.addView(transport)

        // (The floating-app switcher now lives in the right-hand rail, built below.)

        // The volume readouts live in the Car/Signals tabs, not here. On this vehicle the
        // value is the head-unit stage, not what the ear hears (steering and NBT volume
        // run through the car's amplifier), so a block of numbers on the driving surface
        // was clutter that could be misread. Keeping the panel to what is useful in motion.

        // Hotspot as a compact pill with an icon — green when on — so its state reads at a
        // glance and it need not be reached through the quick-settings shade (two pulls). It
        // sizes to its content rather than spanning the column, so it reads as a button, not a
        // banner. Hidden entirely if the platform cannot even report hotspot state.
        // One quick row: hotspot state, and shortcuts into the two settings apps. They open
        // full-screen on purpose — see [openFullScreen].
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (app.hotspotController.isSupported()) {
            hotspotIcon = TextView(this).apply {
                text = "📡"; textSize = 15f; setPadding(0, 0, pad(8), 0)
            }
            hotspotText = TextView(this).apply { textSize = 14f; setTypeface(typeface, Typeface.BOLD) }
            hotspotTile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad(14), pad(9), pad(16), pad(9))
                isClickable = true
                setOnClickListener { toggleHotspot() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad(4) }
                addView(hotspotIcon)
                addView(hotspotText)
            }
            quickRow.addView(hotspotTile)
            renderHotspot(app.hotspotController.state())
        }
        // The vendor's car settings (CAN, camera, steering wheel, factory screens) and plain
        // Android settings — both otherwise several taps away through the launcher.
        quickRow.addView(shortcutPill("\uD83D\uDE97", "Car") { openCarSettings() })
        quickRow.addView(shortcutPill("\uD83E\uDD16", "Android") { openAndroidSettings() })
        content.addView(quickRow)

        // Brightness as a relative − / + control (no slider): a couple of taps to soften a
        // too-bright night or lift a too-dim day. There is no ambient-light sensor on this
        // unit, so nothing can auto-tune it — day/night is the CAN illumination line.
        val brightLabel = TextView(this).apply {
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setTextColor(palette.foreground)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        brightnessLabel = brightLabel
        brightnessTile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(8), pad(4), pad(8), pad(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = pad(12).toFloat()
                setColor(if (palette.night) Color.parseColor("#161618") else Color.parseColor("#F0F0F3"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad(8) }
            addView(transportButton("−", emphasised = false) { stepBrightness(dim = true) })
            addView(brightLabel)
            addView(transportButton("+", emphasised = false) { stepBrightness(dim = false) })
        }
        content.addView(brightnessTile)
        renderBrightness()

        // Keep the media card + toggles top-aligned in the main column.
        content.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // ---------- right-hand rail: Settings (top), app switcher, Exit (bottom) ----------
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, MATCH)
                .apply { leftMargin = pad(10) }
        }
        // Settings behaves like a switcher entry: lit when the config occupies the left tile.
        val settingsTile = glyphTile("⚙", "Settings") { onSettingsTap() }
        this.settingsTile = settingsTile
        setTileSelected(settingsTile, app.layoutRepository.cockpitLeft is CockpitLeft.Config)
        rail.addView(settingsTile)
        // The floating-app switcher, vertical: highlights the active app; a tap switches to it.
        switcherTiles.clear()
        val current = currentFloatingPackage()
        floatableApps().forEach { pkg ->
            val tile = appIcon(pkg, app.appCatalog.labelFor(pkg))
            setTileSelected(tile, pkg == current)
            switcherTiles += pkg to tile
            rail.addView(tile)
        }
        // Exit (back to the vendor launcher) pinned to the bottom of the rail.
        rail.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))
        rail.addView(glyphTile("✕", "Exit") {
            // Un-window every tile and go home, then finish the panel so nothing is left floating.
            // Reset the left-tile state so a later Cockpit re-entry (or autostart) starts clean —
            // not still "hidden" from before the exit.
            app.layoutRepository.cockpitLeft = CockpitLeft.Default
            app.layoutEngine.resetToVendorState()
            finish()
            toast("Returned to the vendor launcher")
        })

        panel.addView(content)
        panel.addView(rail)

        return row
    }

    /**
     * Left-hand fraction covered by the floating window of the active anchored preset.
     *
     * Reserved **only when this activity actually fills the display**. The reservation
     * describes a window floating *over* the panel; when the companion is itself a tile
     * the map sits *beside* it, and reserving a strip inside our own tile squeezes the
     * content into a sliver of a sliver. `[RUNTIME]` 2026-07-31: with a 50/50 tiled
     * layout the panel collapsed to about a quarter of the screen for exactly this reason.
     */
    private enum class MapSide { LEFT, RIGHT }
    private data class Reservation(val side: MapSide, val fraction: Float)

    /**
     * Where the floating app sits and how much width it covers, so the panel leaves that strip
     * empty on the correct side. This is exactly the app-tile geometry the panel is the complement
     * of ([LayoutEngine.cockpitAppBounds]): the left tile is `split` wide on the left, mirrored to
     * the right when swapped. Deriving it from split/swap — rather than re-parsing the preset —
     * keeps the reserved strip in lockstep with the actual tile bounds for *any* floated app, and
     * it is what paints the black strip in the hidden state (the panel content keeps its width).
     *
     * Only reserved when this activity actually fills the display (a narrow tile reserves nothing).
     * `repository.split` is already coerced to `[MIN_SPLIT, MAX_SPLIT]` (≤ 0.8).
     */
    private fun reservation(): Reservation? {
        if (!fillsDisplay()) return null
        val side = if (app.layoutRepository.swapped) MapSide.RIGHT else MapSide.LEFT
        return Reservation(side, app.layoutRepository.split)
    }

    // `statusBarHeightPx()` (vendor strip height) and `fillsDisplay()` (are we a tile or full-screen)
    // are shared with MainActivity — see ui/TileWindow.kt and wits/WindowMetrics.kt.

    // --------------------------------------------------------------- lifecycle

    /**
     * The panel handles `screenSize` itself (see the manifest), so a window resize does not
     * recreate it. But [reservation] depends on our width — a narrow tile reserves nothing, a
     * full-screen panel reserves the map strip — so the view must be rebuilt when the window
     * changes size (tile ↔ full, e.g. hiding/showing the floating app). Re-render the live
     * state into the fresh views afterwards.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(buildRoot())
        renderBrightness()
        if (hotspotTile != null) renderHotspot(app.hotspotController.state())
        latestMedia?.let { onMedia(it) }
        applyImmersive()
        ui.post { ensurePanelBounds() }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        // The rail's highlights are set when the view is built, but the left tile can change while
        // the panel sits in the background — the config screen applies a layout (Cockpit card, a
        // preset) and finishes, and we come back to front without being recreated. Re-read the state
        // so the lit tile matches reality: otherwise the gear stays lit after Settings → Cockpit even
        // though the map is now floating (`[RUNTIME]` 2026-08-17).
        refreshRailSelection()
        // The panel is brought to the front as a freeform window, but a relaunch's setLaunchBounds
        // is ignored once the task exists, so it can arrive full-screen. Correct its own bounds to
        // the intended tile (or full, when hidden). Posted so the window metrics are settled first.
        ui.post { ensurePanelBounds() }
    }

    /** Lights exactly the rail entry the current [CockpitLeft] calls for — gear, one app, or none. */
    private fun refreshRailSelection() {
        settingsTile?.let { setTileSelected(it, app.layoutRepository.cockpitLeft is CockpitLeft.Config) }
        val current = currentFloatingPackage()
        switcherTiles.forEach { (pkg, tile) -> setTileSelected(tile, pkg == current) }
    }

    /**
     * Hides the vendor top strip only in the hidden / full-screen state, so a dismissed-app panel
     * is a clean full-screen surface — exactly what the vendor speedometer dashboard does (its
     * `BaseThemeActivity.setActivityFull()` sets `FLAG_FULLSCREEN`; here the modern
     * `WindowInsetsControllerCompat` equivalent, which the framework renders the same way). A swipe
     * from the top reveals it transiently, then it auto-hides (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE).
     * In the two-tile state the bar is left visible (the map beside us shares it).
     */
    private fun applyImmersive() {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val statusBars = androidx.core.view.WindowInsetsCompat.Type.statusBars()
        if (app.layoutRepository.cockpitLeft is CockpitLeft.Hidden) controller.hide(statusBars)
        else controller.show(statusBars)
    }

    /**
     * Resizes our **own** task (by [getTaskId]) to the bounds the Cockpit wants — the complement
     * tile beside the floating app, or the whole display when hidden. This is what actually makes
     * the panel a right-hand tile instead of a full-screen window drawing over the map (privileged
     * path only; on the emulator the launch bounds already take). A small threshold avoids a
     * resize↔config-change churn on sub-pixel differences.
     */
    private fun ensurePanelBounds() {
        val target = app.layoutEngine.cockpitPanelBounds(
            app.layoutRepository.split,
            app.layoutRepository.swapped,
            hidden = app.layoutRepository.cockpitLeft is CockpitLeft.Hidden,
        )
        matchOwnTaskBounds(app.windowController, target)
    }

    override fun onStart() {
        super.onStart()
        // Start/refresh observation on open: notification access may have been granted since
        // the process started, and the repository is not started elsewhere.
        app.mediaRepository.start()
        app.mediaRepository.ensureObserving()
        if (hotspotTile != null) app.hotspotController.observe(hotspotListener)
        // Follow live brightness changes: the system moves SCREEN_BRIGHTNESS on a day/night
        // switch, and the label would otherwise keep showing the value we last set.
        contentResolver.registerContentObserver(
            android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS),
            false, brightnessObserver,
        )
        ui.post(clockTick)
        // The window may have been resized while stopped (the Cockpit resizes constantly), and
        // only the Activity can answer whether the panel now fills the display.
        model.refresh(fillsDisplay())
    }

    private val brightnessObserver = object : android.database.ContentObserver(ui) {
        override fun onChange(selfChange: Boolean) = renderBrightness()
    }

    override fun onStop() {
        runCatching { contentResolver.unregisterContentObserver(brightnessObserver) }
        if (hotspotTile != null) app.hotspotController.stopObserving()
        ui.removeCallbacks(clockTick)
        super.onStop()
    }


    private val hotspotListener = HotspotController.Listener { state -> ui.post { renderHotspot(state) } }

    private fun renderHotspot(state: HotspotController.State) {
        val tile = hotspotTile ?: return
        val label = hotspotText ?: return
        val on = state == HotspotController.State.ON
        val transitioning = state == HotspotController.State.TURNING_ON ||
            state == HotspotController.State.TURNING_OFF

        // Green when on, neutral otherwise; the colour is the state at a glance.
        val fill = when {
            on -> HOTSPOT_ON
            transitioning -> HOTSPOT_BUSY
            else -> if (palette.night) Color.parseColor("#2A2A2E") else Color.parseColor("#E4E4EA")
        }
        tile.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = pad(20).toFloat(); setColor(fill)
        }
        label.text = when (state) {
            HotspotController.State.ON -> "Hotspot on"
            HotspotController.State.OFF -> "Hotspot"
            HotspotController.State.TURNING_ON -> "Hotspot…"
            HotspotController.State.TURNING_OFF -> "Hotspot…"
            HotspotController.State.FAILED -> "Hotspot failed"
            HotspotController.State.UNKNOWN -> "Hotspot —"
        }
        label.setTextColor(if (on || transitioning) Color.WHITE else palette.foreground)
        tile.isEnabled = app.hotspotController.canToggle() && !transitioning
        tile.alpha = if (tile.isEnabled) 1f else 0.5f
    }

    private fun toggleHotspot() {
        val turnOn = app.hotspotController.state() != HotspotController.State.ON
        app.layoutRepository.hotspotDesiredOn = turnOn  // remember the intent for auto-restore
        app.hotspotController.setEnabled(turnOn) { ok ->
            ui.post {
                if (!ok) toast("Hotspot change failed")
                renderHotspot(app.hotspotController.state())
            }
        }
    }

    private fun renderBrightness() {
        val label = brightnessLabel ?: return
        val pct = app.brightnessController.percent()
        label.text = if (pct != null) "Brightness · $pct%" else "Brightness"
    }

    private fun stepBrightness(dim: Boolean) {
        when (val r = if (dim) app.brightnessController.dim() else app.brightnessController.brighten()) {
            is BrightnessController.Result.Written -> brightnessLabel?.text = "Brightness · ${r.percent}%"
            BrightnessController.Result.PermissionRequired -> {
                toast("Allow ‘modify system settings’ to control brightness")
                runCatching { startActivity(app.brightnessController.permissionIntent()) }
            }
            is BrightnessController.Result.Error -> toast("Brightness: ${r.message}")
            is BrightnessController.Result.NotApplied -> {
                toast("Brightness unchanged: ${r.reason}")
                renderBrightness()
            }
        }
    }

    // ----------------------------------------------------------------- updates

    /** Delivered on the main thread by [CarStateRepository]. */
    /**
     * Applies one published state. The single place the panel is driven from — previously the
     * work was split across two repository callbacks and several direct reads scattered
     * through the render helpers, so what the panel showed depended on which arrived last.
     */
    private fun render(state: CockpitUiState) {
        stateView.text = state.statusText
        renderNavigation(state.navigation)
        state.media.raw?.let { onMedia(it) }
        renderHotspot(state.hotspot.state)
        renderBrightness()
        refreshRailSelection()
    }

    /** Delivered on the main thread by [MediaSessionRepository], via the ViewModel. */
    private fun onMedia(snapshot: MediaSnapshot) {
        if (!snapshot.permissionGranted) {
            trackView.text = "Media access not granted"
            artistView.text = "Settings → Media, or grant notification access"
            setTransportEnabled(prev = false, playPause = false, next = false)
            artView.visibility = View.GONE
            tintMediaCard(null)
            return
        }

        // Three states: a track playing (title present), a live source with no track loaded
        // yet (a session exists — show the app, not a blank panel), or nothing at all.
        val hasTrack = snapshot.available && snapshot.title != null
        val source = snapshot.packageName?.takeIf { snapshot.available }
        val sourceLabel = source?.let { app.appCatalog.labelFor(it) }

        trackView.text = snapshot.title ?: sourceLabel ?: "Nothing playing"
        artistView.text = if (hasTrack) {
            listOfNotNull(snapshot.artist, snapshot.album).joinToString(" · ").ifBlank { sourceLabel ?: "" }
        } else {
            ""
        }
        playPauseButton.text = if (snapshot.isPlaying) "⏸" else "▶"
        setTransportEnabled(
            prev = snapshot.canSkipPrevious,
            // With no live session the player reports no actions at all — but play must stay
            // tappable, because that is exactly the case it is for: it dispatches a media key that
            // wakes the player's playback service (§ MediaSessionRepository.dispatchMediaKey). Left
            // disabled, the fallback could never be reached and the button was simply dead until the
            // user opened Spotify themselves (`[RUNTIME]` 2026-08-17).
            playPause = snapshot.canPlay || snapshot.canPause || !snapshot.available,
            next = snapshot.canSkipNext,
        )

        // Album art, or the player's own icon as a placeholder so an active-but-artless
        // source (before the first track loads, or a logged-out player) still reads as its
        // app rather than blank.
        val art = snapshot.albumArt
        when {
            art != null -> {
                artView.setImageBitmap(art); artView.visibility = View.VISIBLE
            }
            source != null -> {
                artView.setImageDrawable(
                    runCatching { packageManager.getApplicationIcon(source) }.getOrNull()
                )
                artView.visibility = View.VISIBLE
            }
            else -> {
                artView.setImageDrawable(null); artView.visibility = View.GONE
            }
        }

        // The card takes on the colour of the source: the album's accent when a track is
        // loaded, else the player's brand colour, else a neutral card when nothing is live.
        val accent = when {
            hasTrack -> AlbumAccent.from(art) ?: brandColor(source)
            source != null -> brandColor(source)
            else -> null
        }
        tintMediaCard(accent)

        latestMedia = snapshot
        refreshProgress()
    }

    /**
     * Shows or hides the manoeuvre row.
     *
     * Hidden entirely when there is nothing to say, rather than shown empty: an empty strip
     * costs vertical space on a 900 px panel and tells the driver nothing. The distance view
     * is dropped independently, because plenty of instructions arrive without one.
     */
    private fun renderNavigation(nav: CockpitUiState.NavPanel) {
        if (!::navCard.isInitialized) return
        if (!nav.visible) {
            navCard.visibility = View.GONE
            return
        }
        navCard.visibility = View.VISIBLE
        navDistanceView.visibility = if (nav.distance.isNullOrBlank()) View.GONE else View.VISIBLE
        navDistanceView.text = nav.distance.orEmpty()
        // The ETA is appended rather than given its own view: it is context, not the
        // instruction, and a third column would crowd a 35 % tile.
        navInstructionView.text = listOfNotNull(nav.instruction, nav.eta)
            .joinToString("   ·   ")
    }

    /** Enables/greys the three transport glyphs without the default Button wash. */
    private fun setTransportEnabled(prev: Boolean, playPause: Boolean, next: Boolean) {
        prevButton.isEnabled = prev
        prevButton.alpha = if (prev) 1f else 0.35f
        playPauseButton.isEnabled = playPause
        playPauseButton.alpha = if (playPause) 1f else 0.5f
        nextButton.isEnabled = next
        nextButton.alpha = if (next) 1f else 0.35f
    }

    /**
     * Paints the media card from [accent] (or neutral when null).
     *
     * The accent survives only where it is quiet: a soft wash behind the card and the thin progress
     * line. The **play button and the track text stay neutral** — carrying the album/brand colour
     * there made the control glow yellow/orange depending on the cover, which is exactly the kind of
     * loud, shifting colour a driving surface should not have (reported twice, `[RUNTIME]`
     * 2026-08-17). Neutral also fixes the day-mode contrast: a white glyph used to sit on a light
     * fill whenever no accent was available.
     */
    private fun tintMediaCard(accent: Int?) {
        val neutral = if (palette.night) Color.parseColor("#161618") else Color.parseColor("#F0F0F3")
        val a = accent?.let { if (palette.night) it else darken(it, 0.6f) }
        val cardFill = if (a == null) neutral else washed(a, if (palette.night) 0.22f else 0.14f, neutral)
        mediaCard.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = pad(20).toFloat(); setColor(cardFill)
        }
        // One steady neutral in each theme, with a glyph that actually reads against it.
        val button = if (palette.night) Color.parseColor("#3A3A40") else Color.parseColor("#DCDCE2")
        playPauseButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(button)
        }
        playPauseButton.setTextColor(if (palette.night) Color.WHITE else palette.foreground)
        trackView.setTextColor(palette.foreground)
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(a ?: palette.muted)
    }

    /** Blends [color] over [base] at [alpha] — a soft tint without needing alpha compositing. */
    private fun washed(color: Int, alpha: Float, base: Int): Int {
        fun mix(c: Int, b: Int) = (b + (c - b) * alpha).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(color), Color.red(base)),
            mix(Color.green(color), Color.green(base)),
            mix(Color.blue(color), Color.blue(base)),
        )
    }

    /** Brand colour for a known player, so an artless session still has a hue. */
    private fun brandColor(pkg: String?): Int? = when (pkg) {
        WitsPackages.SPOTIFY -> Color.parseColor("#1DB954")
        "com.google.android.apps.youtube.music" -> Color.parseColor("#FF0000")
        "com.google.android.youtube" -> Color.parseColor("#FF0000")
        "com.apple.android.music" -> Color.parseColor("#FA243C")
        "deezer.android.app" -> Color.parseColor("#A238FF")
        else -> null
    }

    /**
     * Extrapolates the position between MediaSession updates: players report a position
     * and the moment it was taken, not a ticking value.
     */
    private fun refreshProgress() {
        val s = latestMedia
        val duration = s?.durationMs ?: 0L
        if (s == null || duration <= 0L) {
            progressBar.visibility = View.INVISIBLE
            progressLabel.text = ""
            return
        }
        val elapsed = if (s.isPlaying) {
            android.os.SystemClock.elapsedRealtime() - s.positionUpdatedElapsedMs
        } else {
            0L
        }
        val position = (s.positionMs + elapsed).coerceIn(0L, duration)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = ((position * PROGRESS_MAX) / duration).toInt()
        progressLabel.text = "${clock(position)} / ${clock(duration)}"
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Scales a colour's brightness toward black by [factor] (0..1), keeping its hue. */
    private fun darken(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= factor.coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /**
     * A switcher tile was tapped. Tapping the **active** app toggles it off — the app is
     * hidden and the panel fills the display (§ [hideFloatingApp]); tapping any other tile
     * floats that app. From the hidden state no tile is active, so any tap floats.
     */
    private fun onSwitcherTap(packageName: String, label: String) {
        if (packageName == currentFloatingPackage()) hideFloatingApp() else floatApp(packageName, label)
    }

    /**
     * Floats [packageName] over the panel, replacing whatever was there.
     *
     * Goes through [LayoutEngine] rather than moving the window directly, so the reverse
     * guard, the rate limiter and the two-phase ordering all still apply.
     */
    private fun floatApp(packageName: String, label: String) {
        val preset = DefaultPresets.anchoredFor(packageName, label)
            .withGeometry(app.layoutRepository.split, app.layoutRepository.swapped)
        when (val r = app.layoutEngine.apply(preset, app.carStateRepository.state, Trigger.USER)) {
            is LayoutEngine.Result.Applied -> {
                app.layoutRepository.lastAppliedPresetId = preset.id
                app.layoutRepository.cockpitLeft = CockpitLeft.App(packageName)
                // Move the highlight onto the chosen app, popping the one just tapped.
                settingsTile?.let { setTileSelected(it, selected = false) }
                switcherTiles.forEach { (pkg, tile) ->
                    setTileSelected(tile, pkg == packageName, animate = pkg == packageName)
                }
                toast("$label over panel")
            }
            is LayoutEngine.Result.Refused -> toast("Refused: ${r.reason}")
            is LayoutEngine.Result.Invalid -> toast("Invalid: ${r.errors.firstOrNull()}")
        }
    }

    /**
     * Hides the currently floating app: the panel grows to fill the display (its reservation
     * paints the freed strip black and keeps the content at its usual width), and no tile is
     * left lit. The window resize triggers [onConfigurationChanged], which rebuilds the view.
     */
    private fun hideFloatingApp() {
        val current = currentFloatingPackage()  // resolve before we clear the state
        app.layoutRepository.cockpitLeft = CockpitLeft.Hidden
        app.layoutEngine.hideFloatingApp(current)
        settingsTile?.let { setTileSelected(it, selected = false) }
        switcherTiles.forEach { (_, tile) -> setTileSelected(tile, selected = false) }
        toast("App hidden")
    }

    /**
     * Shows the config UI ([MainActivity]) in the Cockpit's **left tile** — the slot the map lives
     * in — instead of leaving the Cockpit for a full-screen screen. The old approach un-windowed
     * the tiles and finished the panel, which raced with the ROM's window handling and the
     * autostart panel ("Settings flashes / closes the app to the vendor launcher"). Here nothing
     * is un-windowed or finished: MainActivity is launched freeform at the app-tile bounds and
     * reordered to the front, so it covers the map while the panel stays on the right. There is no
     * race and nothing to bounce.
     */
    /** The Settings gear was tapped: show the config in the left tile and light the gear. */
    private fun onSettingsTap() {
        app.layoutRepository.cockpitLeft = CockpitLeft.Config
        // Call off anything the engine still has queued for the layout we are replacing — in
        // particular the post-apply verification, which would otherwise see "the map is missing"
        // and re-assert the Cockpit over the config the user just opened.
        app.layoutEngine.cancelPending()
        // Move the highlight onto the gear, dropping any app tile.
        settingsTile?.let { setTileSelected(it, selected = true, animate = true) }
        switcherTiles.forEach { (_, tile) -> setTileSelected(tile, selected = false) }
        openConfigInLeftTile()
    }

    private fun openConfigInLeftTile() {
        val bounds = app.layoutEngine.cockpitAppBounds(
            app.layoutRepository.split, app.layoutRepository.swapped,
        )
        val intent = android.content.Intent(this, MainActivity::class.java).addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        ).putExtra(MainActivity.EXTRA_COCKPIT_TILE, true)
        val opts = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)
        runCatching {
            android.app.ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, WitsWindowMode.FREEFORM)
        }
        startActivity(intent, opts.toBundle())
    }

    /**
     * A neutral pill for the quick row, shaped like the hotspot tile so the row reads as one set.
     */
    private fun shortcutPill(glyph: String, label: String, onClick: () -> Unit): LinearLayout {
        val icon = TextView(this).apply { text = glyph; textSize = 15f; setPadding(0, 0, pad(8), 0) }
        val text = TextView(this).apply {
            this.text = label; textSize = 14f
            setTypeface(typeface, Typeface.BOLD); setTextColor(palette.foreground)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(14), pad(9), pad(16), pad(9))
            isClickable = true
            setOnClickListener { onClick() }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = pad(20).toFloat()
                setColor(if (palette.night) Color.parseColor("#2A2A2E") else Color.parseColor("#E4E4EA"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = pad(4); leftMargin = pad(8) }
            addView(icon)
            addView(text)
        }
    }

    /**
     * The vendor's own car settings — CAN, camera, steering wheel, the factory screens.
     * `getLaunchIntentForPackage` first (it is a normal system app), falling back to the known
     * component when it advertises no launcher entry.
     */
    private fun openCarSettings() {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(WitsPackages.WITS_SETTINGS)
            ?: android.content.Intent().setClassName(
                WitsPackages.WITS_SETTINGS, "${WitsPackages.WITS_SETTINGS}.SettingsActivity"
            )
        openFullScreen(intent, "Car settings")
    }

    /** Plain Android settings. */
    private fun openAndroidSettings() =
        openFullScreen(android.content.Intent(android.provider.Settings.ACTION_SETTINGS), "Android settings")

    /**
     * Starts a utility app **full-screen**, deliberately not as a Cockpit tile.
     *
     * Settings screens are dense, full-width UIs visited while parked, and vendor system apps set
     * their own window flags — adopting them into a freeform tile buys little and costs a whole new
     * window path to place, verify and repair. The Cockpit is a driving surface, not a window
     * manager. Coming back is one tap on the companion icon, which re-applies the last layout.
     */
    private fun openFullScreen(intent: android.content.Intent, label: String) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { toast("$label unavailable") }
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ helpers

    /**
     * Up to four apps to offer as the floating window: the vendor's nav and music choices
     * first, then the well-known apps, deduplicated and only if installed and launchable.
     */
    private fun floatableApps(): List<String> {
        val d = app.appCatalog.vendorDefaults()
        val preferred = listOfNotNull(
            d.navigation, d.music, d.video,
            WitsPackages.MAPS, WitsPackages.CHROME, WitsPackages.SPOTIFY,
        )
        return preferred.filter { app.windowController.isLaunchable(it) }.distinct().take(4)
    }

    /**
     * Which app currently floats over the panel, to highlight its tile. Prefers the package
     * remembered directly (survives an activity recreation and resolves the switcher's
     * `anchored_<pkg>` presets, which are not in allPresets); falls back to the last-applied
     * anchored preset, then the default map.
     */
    private fun currentFloatingPackage(): String? = when (val left = app.layoutRepository.cockpitLeft) {
        // A named app floats; the config or an explicit hide means no app tile lights (the gear
        // lights instead / nothing) and any app tap floats that app; Default falls back to the map.
        is CockpitLeft.App -> left.packageName
        CockpitLeft.Hidden, CockpitLeft.Config -> null
        CockpitLeft.Default -> defaultFloatingPackage()
    }

    /** The package the default map anchored preset floats over the panel, or null. */
    private fun defaultFloatingPackage(): String? =
        (app.layoutRepository.lastAppliedPreset()?.takeIf { it.kind == PresetKind.ANCHORED }
            ?: app.layoutRepository.preset(DefaultPresets.ID_MAPS_ANCHORED))
            ?.windows?.firstOrNull { it.packageName != WitsPackages.SELF }?.packageName

    /**
     * A compact app tile: a centred icon over a centred label, in a fixed-width box. The
     * label is full-width and centre-gravity so a longer name stays centred and ellipsises
     * instead of drifting sideways. Stacked vertically in the right-hand rail.
     */
    private fun appIcon(packageName: String, label: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(pad(88), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = pad(4) }
            isClickable = true
            setPadding(pad(6), pad(8), pad(6), pad(8))
            setOnClickListener { onSwitcherTap(packageName, label) }
        }
        box.addView(android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(pad(40), pad(40))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
            setImageDrawable(
                runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
            )
        })
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, pad(3), 0, 0)
        })
        return box
    }

    /**
     * A rail tile that mirrors [appIcon] but uses a glyph instead of an app icon — for the
     * Settings and Exit actions at the ends of the right-hand rail.
     */
    private fun glyphTile(glyph: String, label: String, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(pad(88), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = pad(4) }
            isClickable = true
            setPadding(pad(6), pad(8), pad(6), pad(8))
            setOnClickListener { onClick() }
        }
        box.addView(TextView(this).apply {
            text = glyph
            textSize = 22f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(palette.foreground)
            layoutParams = LinearLayout.LayoutParams(pad(40), pad(40))
        })
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            maxLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(palette.muted)
            layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, pad(3), 0, 0)
        })
        return box
    }

    /**
     * Marks a switcher tile as the active floating app. The selected tile gets a soft filled
     * pill and a bold label; the others are dimmed so the active one is obvious at a glance —
     * no outline, which read as too heavy. When [animate] is set, the tile pops in — a small
     * motion so a tap visibly *moves the focus* onto the app just chosen.
     */
    private fun setTileSelected(tile: LinearLayout, selected: Boolean, animate: Boolean = false) {
        tile.background = if (selected) {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = pad(16).toFloat()
                setColor(if (palette.night) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000"))
            }
        } else null
        tile.alpha = if (selected) 1f else 0.55f
        (tile.getChildAt(1) as? TextView)?.apply {
            setTextColor(if (selected) palette.foreground else palette.muted)
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        if (selected && animate) {
            tile.scaleX = 0.88f; tile.scaleY = 0.88f
            tile.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
        } else {
            tile.animate().cancel()
            tile.scaleX = 1f; tile.scaleY = 1f
        }
    }

    /**
     * A round transport glyph. The emphasised one (play/pause) is a filled accent circle
     * with a white glyph; the skips are borderless. A [TextView] rather than a Button so
     * there is no all-caps wash or default min-size, and the fill can be re-tinted per track.
     */
    private fun transportButton(glyph: String, emphasised: Boolean, onClick: () -> Unit) =
        TextView(this).apply {
            text = glyph
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = if (emphasised) 24f else 21f
            setTextColor(if (emphasised) Color.WHITE else palette.foreground)
            val size = if (emphasised) pad(66) else pad(54)
            layoutParams = LinearLayout.LayoutParams(size, size)
                .apply { leftMargin = pad(12); rightMargin = pad(12) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun pad(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /**
     * The panel's colours for the current day/night state.
     *
     * Read from the system `uiMode` night bit — the **same** signal Google Maps and every
     * other app read — so the floating map and the panel are dark or light together. The
     * panel used to be hard-coded dark, which is why a daytime (headlights-off) drive
     * showed a light map beside a dark panel: `[RUNTIME]` 2026-07-31. On this head unit
     * day/night tracks the headlights (illumination), so the state flips with the lights,
     * not the clock.
     *
     * uiMode is not in the activity's configChanges, so a day/night flip recreates the
     * activity and this is recomputed in onCreate — no manual listener needed.
     */
    private data class Palette(
        val background: Int,
        val foreground: Int,
        val muted: Int,
        val night: Boolean,
    )

    private val palette: Palette by lazy {
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (night) {
            Palette(Color.BLACK, Color.WHITE, Color.parseColor("#9E9E9E"), night = true)
        } else {
            Palette(Color.parseColor("#FAFAFA"), Color.parseColor("#212121"),
                Color.parseColor("#616161"), night = false)
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** Clock and track position share one tick. */
        const val TICK_MS = 1_000L
        const val PROGRESS_MAX = 1_000

        val HOTSPOT_ON = Color.parseColor("#2E7D32")   // green
        val HOTSPOT_BUSY = Color.parseColor("#F9A825") // amber, transitioning


        val CLOCK = SimpleDateFormat("HH:mm", Locale.US)
    }
}
