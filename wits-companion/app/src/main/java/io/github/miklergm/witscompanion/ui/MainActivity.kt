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
        val floor = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) } ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, maxOf(bars.top, floor), bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as WitsCompanionApp
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

    override fun onResume() {
        super.onResume()
        // While the config UI is up, suppress the autostart panel — set BEFORE onActivityResumed
        // (which itself may try to restore) so tapping Settings can't be bounced back to the
        // Cockpit. Cleared in onPause.
        app.recoveryCoordinator.configUiVisible = true
        current?.onResume()
        // Opt-in only; disabled by default. Never switches the source.
        app.recoveryCoordinator.onActivityResumed(app.carStateRepository.state)
    }

    override fun onPause() {
        app.recoveryCoordinator.configUiVisible = false
        current?.onPause()
        super.onPause()
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
}
