package io.github.miklergm.witscompanion.ui

import android.app.Activity
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
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutEngine
import io.github.miklergm.witscompanion.layout.PresetKind
import io.github.miklergm.witscompanion.media.MediaSessionRepository
import io.github.miklergm.witscompanion.media.MediaSnapshot
import io.github.miklergm.witscompanion.safety.Trigger
import io.github.miklergm.witscompanion.wits.BrightnessController
import io.github.miklergm.witscompanion.wits.HotspotController
import io.github.miklergm.witscompanion.wits.WitsPackages
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
class DashboardActivity : Activity(), CarStateRepository.Observer, MediaSessionRepository.Listener {

    private lateinit var app: WitsCompanionApp

    private lateinit var clockView: TextView
    private lateinit var stateView: TextView
    private lateinit var trackView: TextView
    private lateinit var artistView: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var mediaCard: LinearLayout
    private var hotspotTile: LinearLayout? = null
    private var hotspotText: TextView? = null
    private var brightnessTile: LinearLayout? = null
    private var brightnessLabel: TextView? = null
    private lateinit var artView: android.widget.ImageView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var progressLabel: TextView

    /** Floating-app switcher tiles, kept so the highlight can move on selection. */
    private val switcherTiles = mutableListOf<Pair<String, LinearLayout>>()

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
        setContentView(buildRoot())
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
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad(16), pad(14), pad(12), pad(14))
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

        // Hotspot as a coloured tile — green when on — so its state reads at a glance and
        // it need not be reached through the quick-settings shade (two pulls). Hidden
        // entirely if the platform cannot even report hotspot state.
        if (app.hotspotController.isSupported()) {
            hotspotText = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
            hotspotTile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad(16), pad(12), pad(16), pad(12))
                isClickable = true
                setOnClickListener { toggleHotspot() }
                layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = pad(4) }
                addView(hotspotText)
            }
            content.addView(hotspotTile)
            renderHotspot(app.hotspotController.state())
        }

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
        rail.addView(glyphTile("⚙", "Settings") {
            // Leaving the Cockpit: un-window the tiles (otherwise the freeform map keeps drawing
            // over MainActivity — the "Settings just flashes" report) and finish the panel, then
            // show the config UI.
            app.layoutEngine.unwindowTiles(thenGoHome = false)
            startActivity(android.content.Intent(this@DashboardActivity, MainActivity::class.java))
            finish()
        })
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
     * Where the floating map sits and how much width it covers, so the panel leaves that
     * strip empty on the correct side. The map can be anchored left or right (the split's
     * swap); a window that is neither edge-flush reserves nothing (full-width panel).
     * Only reserved when this activity actually fills the display (§ fillsDisplay).
     */
    private fun reservation(): Reservation? {
        if (!fillsDisplay()) return null
        val preset = app.layoutRepository.lastAppliedPreset()
            ?.takeIf { it.kind == PresetKind.ANCHORED }
            ?: app.layoutRepository.preset(DefaultPresets.ID_MAPS_ANCHORED)
            ?: return null
        val window = preset.windows.firstOrNull { it.packageName != WitsPackages.SELF } ?: return null
        val b = window.bounds
        val cap = 0.8f
        return when {
            b.left <= 0.01f && b.right < 0.99f -> Reservation(MapSide.LEFT, b.right.coerceAtMost(cap))
            b.right >= 0.99f && b.left > 0.01f -> Reservation(MapSide.RIGHT, (1f - b.left).coerceAtMost(cap))
            else -> null
        }
    }

    /** True when our window is (near enough) as wide as the whole display. */
    private fun fillsDisplay(): Boolean = runCatching {
        val wm = getSystemService(android.view.WindowManager::class.java)
        val own = wm.currentWindowMetrics.bounds.width()
        val display = wm.maximumWindowMetrics.bounds.width()
        display > 0 && own * 100 >= display * FULL_WIDTH_PERCENT
    }.getOrDefault(true)

    // --------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        app.carStateRepository.addObserver(this)
        // Start/refresh observation on open: notification access may have been granted since
        // the process started, and the repository is not started elsewhere.
        app.mediaRepository.start()
        app.mediaRepository.ensureObserving()
        app.mediaRepository.addListener(this)
        if (hotspotTile != null) {
            app.hotspotController.observe(hotspotListener)
            renderHotspot(app.hotspotController.state())
        }
        ui.post(clockTick)
        onCarState(app.carStateRepository.state)
    }

    override fun onStop() {
        app.carStateRepository.removeObserver(this)
        app.mediaRepository.removeListener(this)
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
            cornerRadius = pad(12).toFloat(); setColor(fill)
        }
        label.text = when (state) {
            HotspotController.State.ON -> "Hotspot  ·  ON"
            HotspotController.State.OFF -> "Hotspot  ·  off"
            HotspotController.State.TURNING_ON -> "Hotspot  ·  turning on…"
            HotspotController.State.TURNING_OFF -> "Hotspot  ·  turning off…"
            HotspotController.State.FAILED -> "Hotspot  ·  failed"
            HotspotController.State.UNKNOWN -> "Hotspot  ·  —"
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
        }
    }

    // ----------------------------------------------------------------- updates

    /** Delivered on the main thread by [CarStateRepository]. */
    override fun onCarState(state: CarState) {
        stateView.text = buildString {
            append(state.sourceName)
            append("   ACC ").append(state.acc.display())
            if (state.reverseActive == true) append("   REVERSE")
        }
    }

    /** Delivered on the main thread by [MediaSessionRepository]. */
    override fun onMedia(snapshot: MediaSnapshot) {
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
            playPause = snapshot.canPlay || snapshot.canPause,
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
     * Paints the media card and the play button with [accent] (or neutral when null).
     * The card fill is a soft wash of the accent so text stays readable; the play button
     * is the solid accent. In day mode the accent is darkened for contrast on the light
     * panel; the track text follows so it reads against the wash.
     */
    private fun tintMediaCard(accent: Int?) {
        val neutral = if (palette.night) Color.parseColor("#161618") else Color.parseColor("#F0F0F3")
        val a = accent?.let { if (palette.night) it else darken(it, 0.6f) }
        val cardFill = if (a == null) neutral else washed(a, if (palette.night) 0.22f else 0.14f, neutral)
        mediaCard.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = pad(20).toFloat(); setColor(cardFill)
        }
        val button = a ?: (if (palette.night) Color.parseColor("#3A3A40") else Color.parseColor("#C8C8CE"))
        playPauseButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(button)
        }
        trackView.setTextColor(if (a == null) palette.foreground else a)
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
                app.layoutRepository.cockpitFloatingPackage = packageName
                // Move the highlight onto the chosen app, popping the one just tapped.
                switcherTiles.forEach { (pkg, tile) ->
                    setTileSelected(tile, pkg == packageName, animate = pkg == packageName)
                }
                toast("$label over panel")
            }
            is LayoutEngine.Result.Refused -> toast("Refused: ${r.reason}")
            is LayoutEngine.Result.Invalid -> toast("Invalid: ${r.errors.firstOrNull()}")
        }
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
    private fun currentFloatingPackage(): String? =
        app.layoutRepository.cockpitFloatingPackage
            ?: (app.layoutRepository.lastAppliedPreset()?.takeIf { it.kind == PresetKind.ANCHORED }
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
            setOnClickListener { floatApp(packageName, label) }
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
     * Marks a switcher tile as the active floating app. The selected tile gets a filled pill
     * with an outline and a bold label; the others are dimmed so the active one is obvious at
     * a glance. When [animate] is set, the tile pops in — a small motion so a tap visibly
     * *moves the focus* onto the app just chosen.
     */
    private fun setTileSelected(tile: LinearLayout, selected: Boolean, animate: Boolean = false) {
        tile.background = if (selected) {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = pad(16).toFloat()
                setColor(if (palette.night) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000"))
                setStroke(pad(2), palette.foreground)
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

        /** How wide our window must be, as a percentage of the display, to count as the anchor. */
        const val FULL_WIDTH_PERCENT = 90

        val HOTSPOT_ON = Color.parseColor("#2E7D32")   // green
        val HOTSPOT_BUSY = Color.parseColor("#F9A825") // amber, transitioning


        val CLOCK = SimpleDateFormat("HH:mm", Locale.US)
    }
}
