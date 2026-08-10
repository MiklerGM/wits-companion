package io.github.miklergm.witscompanion.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.tabs.TabLayout
import io.github.miklergm.witscompanion.R
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.CarStateRepository
import io.github.miklergm.witscompanion.databinding.ActivityMainBinding
import io.github.miklergm.witscompanion.wits.statusBarHeightPx

/**
 * Single-activity host. Each tab swaps a section view into the content frame.
 *
 * The activity performs no action on start: opening the app never moves a window,
 * never switches the source and never writes a setting.
 */
class MainActivity : AppCompatActivity(), CarStateRepository.Observer {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: WitsCompanionApp

    private val sections = mutableListOf<Section>()
    private var current: Section? = null

    interface Section {
        val title: String
        fun onCreateView(activity: MainActivity): android.view.View
        fun onCarState(state: CarState) {}
        fun onResume() {}
        fun onPause() {}
    }

    /**
     * Keep the content clear of the vendor 99 px top strip.
     *
     * The strip is a standard status bar (`ITYPE_STATUS_BAR`), but this head unit intermittently
     * reports a **zero** top inset — the bar is a per-window overlay, and after the Cockpit's
     * freeform churn the decor comes back without it. With the default decor-fits behaviour that
     * makes the content tuck under the strip: reproducible as "open the Cockpit, come back to
     * Settings, and Settings flies under the top bar".
     *
     * So we take the insets over and floor the top with the framework `status_bar_height`,
     * exactly as [io.github.miklergm.witscompanion.wits.WitsWindowController.usableArea] does for
     * the tile geometry. The padding is a persistent view property, so once floored it stays put
     * even if a later inset pass reports zero. Left/right/bottom pass through for freeform safety.
     */
    private fun keepClearOfTopStrip(root: android.view.View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val floor = statusBarHeightPx()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Floor the top with the strip height only when we FILL the display (standalone). In
            // the Cockpit's left tile the window already sits below the bar, so flooring would
            // double-inset and leave a big empty gap at the top of the config.
            val top = if (fillsDisplay()) maxOf(bars.top, floor) else bars.top
            v.setPadding(bars.left, top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    // `fillsDisplay()` is shared with DashboardActivity — see ui/TileWindow.kt.

    /** True when opened as the Cockpit's left tile (freeform beside the panel), not standalone. */
    private var isCockpitTile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as WitsCompanionApp
        isCockpitTile = intent?.getBooleanExtra(EXTRA_COCKPIT_TILE, false) ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keepClearOfTopStrip(binding.root)

        // Primary tabs first (what you use), diagnostics last (Car / Signals / Debug).
        sections += DashboardSection(app)   // Home
        sections += LayoutsSection(app)
        sections += SettingsSection(app)    // Day/Night
        sections += CarStateSection(app)
        sections += SignalExplorerSection(app)
        sections += DebugSection(app)

        sections.forEach { s ->
            binding.tabs.addTab(binding.tabs.newTab().setText(s.title))
        }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = show(sections[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        show(sections.first())
        app.carStateRepository.addObserver(this)
    }

    /**
     * Swaps in a section's view.
     *
     * The view is built **before** the frame is cleared, so a section that fails to
     * inflate leaves the previous content on screen instead of a blank frame. If it does
     * fail, the reason is shown in place — a silent empty tab is the one outcome that
     * must never happen, because it looks identical to "the app is broken" and carries no
     * information. `[RUNTIME]` 2026-07-31: all tabs were reported blank once, not
     * reproducible; most likely the companion's own window had been shrunk to a sliver by
     * the `usableArea` defect (see docs/window-management.md §6.0.1), but nothing in the
     * UI would have said so.
     */
    private fun show(section: Section) {
        val view = runCatching { section.onCreateView(this) }
            .getOrElse { t -> errorView(section, t) }
        current?.onPause()
        binding.content.removeAllViews()
        binding.content.addView(view)
        current = section
        runCatching { section.onCarState(app.carStateRepository.state) }
        runCatching { section.onResume() }
    }

    private fun errorView(section: Section, t: Throwable): android.view.View {
        val metrics = runCatching {
            val b = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
            "window ${b.width()}x${b.height()}"
        }.getOrDefault("window size unknown")
        android.util.Log.e("MainActivity", "section ${section.title} failed to inflate", t)
        return android.widget.TextView(this).apply {
            text = "Section \"${section.title}\" failed to open.\n\n" +
                "$metrics\n\n${t.javaClass.simpleName}: ${t.message}"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        isCockpitTile = intent?.getBooleanExtra(EXTRA_COCKPIT_TILE, false) ?: false
        window.decorView.post { ensureConfigTileBounds() }
    }

    override fun onResume() {
        super.onResume()
        // Suppress the auto-restore ONLY when we are the Cockpit's config tile — otherwise a
        // reassert would re-float the map over us. A standalone open (from the launcher) must NOT
        // suppress it, or the normal "open the app → Cockpit" autostart would never fire.
        app.recoveryCoordinator.configUiVisible = isCockpitTile
        current?.onResume()
        // Opt-in only; disabled by default. Never switches the source.
        app.recoveryCoordinator.onActivityResumed(app.carStateRepository.state)
        // A relaunch's setLaunchBounds is ignored once our task exists, so correct our own bounds
        // to the app (left) tile. Posted so the window metrics have settled.
        if (isCockpitTile) window.decorView.post { ensureConfigTileBounds() }
    }

    override fun onPause() {
        app.recoveryCoordinator.configUiVisible = false
        current?.onPause()
        super.onPause()
    }

    /**
     * When shown as the Cockpit's left tile, resize our OWN task (by [getTaskId]) to the app-tile
     * bounds — mirrors [DashboardActivity]'s panel self-resize. Privileged path only.
     */
    private fun ensureConfigTileBounds() {
        if (!isCockpitTile) return
        val target = app.layoutEngine.cockpitAppBounds(
            app.layoutRepository.split, app.layoutRepository.swapped,
        )
        matchOwnTaskBounds(app.windowController, target)
    }

    override fun onDestroy() {
        app.carStateRepository.removeObserver(this)
        super.onDestroy()
    }

    override fun onCarState(state: CarState) {
        current?.onCarState(state)
        // The old global "Source / ACC / signals" status bar was removed: it read "—" for
        // most of the drive, and by the time the source is OEM this Android screen is not
        // visible anyway, so it was confusing rather than informative. Live car state lives
        // on the Car tab; the Cockpit shows what matters while driving.
    }

    @Deprecated("Simple SAF callback; adequate for a single export action")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LogExportHelper.REQUEST_CODE_TEXT && resultCode == RESULT_OK) {
            val uri = data?.data
            toast(if (uri != null && LogExportHelper.writeText(this, uri)) "Exported" else "Export failed")
            return
        }
        if (requestCode == LogExportHelper.REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null && LogExportHelper.write(this, app, uri)) {
                toast("Log exported")
            } else {
                toast("Export failed")
            }
        }
    }

    /** Observation only: never consumes the event. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        app.signalExplorer.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    /** Re-inflates the visible section, e.g. after a preset was tweaked. */
    fun refreshCurrentSection() {
        current?.let { show(it) }
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * Set by the Cockpit's Settings gear when it opens this activity as the **left tile** of
         * the Cockpit (freeform, beside the panel) rather than a standalone full-screen screen.
         * Reserved for tile-specific behaviour (e.g. self-resizing the task to the app-tile bounds).
         */
        const val EXTRA_COCKPIT_TILE = "cockpit_tile"
    }
}
