package io.github.miklergm.witscompanion.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as WitsCompanionApp
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sections += DashboardSection(app)
        sections += LayoutsSection(app)
        sections += CarStateSection(app)
        sections += SettingsSection(app)
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
        current?.onResume()
        // Opt-in only; disabled by default. Never switches the source.
        app.recoveryCoordinator.onActivityResumed(app.carStateRepository.state)
    }

    override fun onPause() {
        current?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        app.carStateRepository.removeObserver(this)
        super.onDestroy()
    }

    override fun onCarState(state: CarState) {
        current?.onCarState(state)
        binding.statusBar.text = getString(
            R.string.status_line,
            state.sourceName,
            state.acc.display(),
            if (state.reverseActive == true) "REVERSE" else "—",
            state.observedCount(),
        )
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
