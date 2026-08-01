package io.github.miklergm.witscompanion.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private lateinit var playPauseButton: Button
    private var hotspotButton: Button? = null
    private lateinit var artView: android.widget.ImageView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var progressLabel: TextView

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
        val reserved = reservedFractionLeft()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.background)
        }

        if (reserved > 0f) {
            // Empty spacer: this is where the map floats. Nothing may be drawn here.
            row.addView(View(this), LinearLayout.LayoutParams(0, MATCH, reserved))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(20), pad(16), pad(20), pad(16))
        }
        row.addView(panel, LinearLayout.LayoutParams(0, MATCH, 1f - reserved))

        clockView = TextView(this).apply {
            textSize = 34f; setTextColor(palette.foreground); typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(clockView)

        stateView = TextView(this).apply {
            textSize = 13f; setTextColor(palette.muted); setPadding(0, pad(2), 0, pad(14))
        }
        panel.addView(stateView)

        // Album art beside the track text, so the media block reads at a glance.
        val nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artView = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(pad(72), pad(72))
                .apply { rightMargin = pad(12) }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        nowPlaying.addView(artView)

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        trackView = TextView(this).apply {
            textSize = 22f; setTextColor(palette.foreground); maxLines = 2
        }
        texts.addView(trackView)

        artistView = TextView(this).apply {
            textSize = 15f; setTextColor(palette.muted); maxLines = 1; setPadding(0, pad(2), 0, 0)
        }
        texts.addView(artistView)
        nowPlaying.addView(texts)
        panel.addView(nowPlaying)
        panel.addView(View(this), LinearLayout.LayoutParams(MATCH, pad(12)))

        progressBar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = PROGRESS_MAX
            layoutParams = LinearLayout.LayoutParams(MATCH, pad(3))
            visibility = View.INVISIBLE
        }
        panel.addView(progressBar)

        progressLabel = TextView(this).apply {
            textSize = 11f; setTextColor(palette.muted); typeface = Typeface.MONOSPACE
            setPadding(0, pad(3), 0, pad(8))
        }
        panel.addView(progressLabel)

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        transport.addView(bigButton("<<") { app.mediaRepository.previous() })
        playPauseButton = bigButton("Play") { app.mediaRepository.playPause() }
        transport.addView(playPauseButton)
        transport.addView(bigButton(">>") { app.mediaRepository.next() })
        panel.addView(transport)

        // Which app floats over the panel. Re-applies the anchored layout with the chosen
        // package, so switching is one tap instead of a trip to the Layouts tab.
        panel.addView(TextView(this).apply {
            text = "Floating app"
            textSize = 12f; setTextColor(palette.muted); setPadding(0, pad(16), 0, pad(4))
        })
        // Real launcher icons rather than text: recognisable at a glance and a bigger
        // touch target, the one idea from Mini AA's NavRail that costs nothing here.
        // The apps offered come from the vendor's nav/music choices plus the well-known
        // ones, so the switcher reflects what the user actually set, not a fixed triple.
        val switcher = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        floatableApps().forEach { pkg ->
            switcher.addView(appIcon(pkg, app.appCatalog.labelFor(pkg)))
        }
        panel.addView(switcher)

        // The volume readouts live in the Car/Signals tabs, not here. On this vehicle the
        // value is the head-unit stage, not what the ear hears (steering and NBT volume
        // run through the car's amplifier), so a block of numbers on the driving surface
        // was clutter that could be misread. Keeping the panel to what is useful in motion.

        // Hotspot status + one-tap toggle, so it need not be reached through the
        // quick-settings shade (which takes two pulls). Hidden entirely if the platform
        // cannot even report hotspot state.
        if (app.hotspotController.isSupported()) {
            hotspotButton = Button(this).apply {
                isAllCaps = false
                setOnClickListener { toggleHotspot() }
            }
            panel.addView(hotspotButton)
            renderHotspot(app.hotspotController.state())
        }

        panel.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Settings and a one-tap reset side by side. Reset is on the panel deliberately:
        // if a layout looks wrong while driving, returning to the vendor launcher must be
        // reachable without hunting through tabs.
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(Button(this).apply {
            text = "Settings"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                startActivity(android.content.Intent(this@DashboardActivity, MainActivity::class.java))
            }
        })
        footer.addView(Button(this).apply {
            text = "Reset"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                app.layoutEngine.resetToVendorState()
                toast("Returned to the vendor launcher")
            }
        })
        panel.addView(footer)

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
    private fun reservedFractionLeft(): Float {
        if (!fillsDisplay()) return 0f
        val preset = app.layoutRepository.lastAppliedPreset()
            ?.takeIf { it.kind == PresetKind.ANCHORED }
            ?: app.layoutRepository.preset(DefaultPresets.ID_MAPS_ANCHORED)
            ?: return 0f
        return preset.anchorReservedLeftFraction()
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
        app.mediaRepository.addListener(this)
        if (hotspotButton != null) {
            app.hotspotController.observe(hotspotListener)
            renderHotspot(app.hotspotController.state())
        }
        ui.post(clockTick)
        onCarState(app.carStateRepository.state)
    }

    override fun onStop() {
        app.carStateRepository.removeObserver(this)
        app.mediaRepository.removeListener(this)
        if (hotspotButton != null) app.hotspotController.stopObserving()
        ui.removeCallbacks(clockTick)
        super.onStop()
    }

    private val hotspotListener = HotspotController.Listener { state -> ui.post { renderHotspot(state) } }

    private fun renderHotspot(state: HotspotController.State) {
        val b = hotspotButton ?: return
        val canToggle = app.hotspotController.canToggle()
        b.text = when (state) {
            HotspotController.State.ON -> "Hotspot: ON" + if (canToggle) "  (tap to turn off)" else ""
            HotspotController.State.OFF -> "Hotspot: off" + if (canToggle) "  (tap to turn on)" else ""
            HotspotController.State.TURNING_ON -> "Hotspot: turning on…"
            HotspotController.State.TURNING_OFF -> "Hotspot: turning off…"
            HotspotController.State.FAILED -> "Hotspot: failed"
            HotspotController.State.UNKNOWN -> "Hotspot: —"
        }
        b.isEnabled = canToggle &&
            state != HotspotController.State.TURNING_ON &&
            state != HotspotController.State.TURNING_OFF
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
            artistView.text = "Settings -> notification access"
            playPauseButton.isEnabled = false
            artView.visibility = View.GONE
            return
        }
        trackView.text = snapshot.title ?: "—"
        artistView.text = listOfNotNull(snapshot.artist, snapshot.packageName).joinToString(" · ")
        playPauseButton.text = if (snapshot.isPlaying) "Pause" else "Play"
        playPauseButton.isEnabled = snapshot.canPlay || snapshot.canPause

        val art = snapshot.albumArt
        if (art != null) {
            artView.setImageBitmap(art)
            artView.visibility = View.VISIBLE
        } else {
            artView.setImageDrawable(null)
            artView.visibility = View.GONE
        }

        // The panel takes on the colour of what is playing; falls back to the plain
        // foreground when the art has no usable hue, rather than tinting everything grey.
        // In day mode the accent is darkened so it stays readable on the light background.
        val raw = AlbumAccent.from(art)
        val accent = when {
            raw == null -> palette.foreground
            palette.night -> raw
            else -> darken(raw, 0.55f)
        }
        trackView.setTextColor(accent)
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(accent)

        latestMedia = snapshot
        refreshProgress()
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
     * A compact app tile: icon and label kept together in a fixed-width box, so a row of
     * two or three does not spread to the screen edges. Packed from the left rather than
     * stretched by weight.
     */
    private fun appIcon(packageName: String, label: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(pad(96), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = pad(8) }
            isClickable = true
            setPadding(pad(6), pad(8), pad(6), pad(8))
            setOnClickListener { floatApp(packageName, label) }
        }
        box.addView(android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(pad(44), pad(44))
            setImageDrawable(
                runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
            )
        })
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            maxLines = 1
            setTextColor(palette.muted)
            setPadding(0, pad(4), 0, 0)
        })
        return box
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = pad(6) }
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


        val CLOCK = SimpleDateFormat("HH:mm", Locale.US)
    }
}
