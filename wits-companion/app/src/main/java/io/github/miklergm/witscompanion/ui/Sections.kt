package io.github.miklergm.witscompanion.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.PropertyReader
import io.github.miklergm.witscompanion.layout.DefaultPresets
import io.github.miklergm.witscompanion.layout.LayoutEngine
import io.github.miklergm.witscompanion.layout.LayoutPreset
import io.github.miklergm.witscompanion.layout.LayoutValidator
import io.github.miklergm.witscompanion.layout.PresetKind
import io.github.miklergm.witscompanion.media.MediaSnapshot
import io.github.miklergm.witscompanion.safety.Trigger
import io.github.miklergm.witscompanion.wits.WitsNightModeController
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsSettingsKeys
import io.github.miklergm.witscompanion.wits.WitsSourceController
import io.github.miklergm.witscompanion.wits.WitsWindowMode

// ------------------------------------------------------------------ view helpers

private fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(12), dp(16), dp(24))
}

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

private fun Context.heading(text: String) = TextView(this).apply {
    this.text = text
    textSize = 16f
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(16), 0, dp(6))
}

private fun Context.body(text: String, mono: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = if (mono) 12f else 14f
    if (mono) typeface = Typeface.MONOSPACE
    setPadding(0, dp(2), 0, dp(2))
}

private fun Context.button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }
}

private fun Context.check(text: String, initial: Boolean, onChange: (Boolean) -> Unit) =
    CheckBox(this).apply {
        this.text = text
        isChecked = initial
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

private fun Context.scroll(content: View) = ScrollView(this).apply { addView(content) }

/** Resolves a theme colour attribute, so cards follow the Material DayNight theme. */
private fun Context.attrColor(attr: Int, fallback: Int = Color.GRAY): Int =
    com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)

/** A rounded rectangle with a ripple clipped to its corners. */
private fun roundedRipple(fill: Int, radius: Float, ripple: Int): android.graphics.drawable.Drawable {
    val content = android.graphics.drawable.GradientDrawable().apply {
        setColor(fill); cornerRadius = radius
    }
    val mask = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.WHITE); cornerRadius = radius
    }
    return android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(ripple), content, mask,
    )
}

/**
 * A compact launch card: app icons on the left, a short title on the right, tappable.
 * Horizontal and rounded so a list of them reads as distinct cards without much height.
 * [primary] gives the Cockpit card a stronger accent.
 */
private fun Context.launchTile(
    title: String,
    packages: List<String>,
    subtitle: String? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
): View {
    // Colours from the Material DayNight theme, so the cards flip with the system setting.
    val fill = attrColor(
        if (primary) com.google.android.material.R.attr.colorPrimary
        else com.google.android.material.R.attr.colorSurfaceVariant
    )
    val titleColor = attrColor(
        if (primary) com.google.android.material.R.attr.colorOnPrimary
        else com.google.android.material.R.attr.colorOnSurface
    )
    val subColor = attrColor(
        if (primary) com.google.android.material.R.attr.colorOnPrimary
        else com.google.android.material.R.attr.colorOnSurfaceVariant
    )
    val ripple = attrColor(android.R.attr.colorControlHighlight)

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        background = roundedRipple(fill, dp(14).toFloat(), ripple)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    val icons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(12) }
    }
    packages.forEach { pkg ->
        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return@forEach
        icons.addView(android.widget.ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) }
        })
    }
    if (icons.childCount > 0) card.addView(icons)

    val text = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    text.addView(TextView(this).apply {
        this.text = title
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(titleColor)
    })
    if (subtitle != null) text.addView(TextView(this).apply {
        this.text = subtitle; textSize = 12f
        setTextColor(subColor)
        if (primary) alpha = 0.85f
    })
    card.addView(text)
    return card
}

// ------------------------------------------------------------------ Dashboard

class DashboardSection(private val app: WitsCompanionApp) : MainActivity.Section {
    // "Home" is the launcher: one tap per layout, plus the Cockpit. Not the driving
    // dashboard itself — that is the Cockpit (DashboardActivity).
    override val title = "Home"

    override fun onCreateView(activity: MainActivity): View {
        val c = activity.column()
        val repo = app.layoutRepository

        // Cockpit — the primary, accented card, spanning the full width.
        c.addView(activity.launchTile(
            title = "Cockpit",
            subtitle = "map + panel",
            packages = listOfNotNull(cockpitFloatPackage()),
            primary = true,
        ) { openCockpit(activity) })

        // Side-by-side layouts flow into as many columns as the width allows: a grid on the
        // wide head-unit display, a single column on a narrow one.
        val grid = FlowLayout(activity).apply {
            hGap = activity.dp(10); vGap = activity.dp(10)
            setPadding(0, activity.dp(10), 0, 0)
        }
        val tileWidth = activity.dp(360)
        repo.allPresets().filter { it.windows.size >= 2 && it.kind == PresetKind.TILED }.forEach { preset ->
            val installed = preset.windows.all { app.windowController.isLaunchable(it.packageName) }
            val ratio = preset.splitFraction()?.let { "${(it * 100).toInt()}/${100 - (it * 100).toInt()}" }
            val card = activity.launchTile(
                title = tileTitle(preset),
                subtitle = if (!installed) "not installed" else ratio,
                packages = preset.windows.map { it.packageName },
            ) { applyFromHome(activity, preset) }
            // Fixed width so the flow can grid them; height wraps content.
            card.layoutParams = ViewGroup.MarginLayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            grid.addView(card)
        }
        c.addView(grid)
        return activity.scroll(c)
    }

    /** A clean tile name from the apps themselves, e.g. "Maps + Chrome" — not the preset's
     *  geometry-mangled title (which carries "(swapped)" and the split). */
    private fun tileTitle(preset: LayoutPreset): String =
        preset.windows.sortedBy { it.bounds.left }.joinToString("  +  ") { w ->
            if (w.packageName == WitsPackages.SELF) "Panel" else app.appCatalog.labelFor(w.packageName)
        }

    /** The app that floats over the Cockpit panel, for the tile's icon. */
    private fun cockpitFloatPackage(): String? =
        app.layoutRepository.preset(DefaultPresets.ID_MAPS_ANCHORED)
            ?.windows?.firstOrNull { it.packageName != WitsPackages.SELF }?.packageName

    private fun openCockpit(activity: MainActivity) {
        val anchored = app.layoutRepository.preset(DefaultPresets.ID_MAPS_ANCHORED)
        if (anchored != null) {
            app.layoutEngine.apply(anchored, app.carStateRepository.state, Trigger.USER)
            app.layoutRepository.lastAppliedPresetId = anchored.id
        }
        activity.startActivity(android.content.Intent(activity, DashboardActivity::class.java))
    }

    private fun applyFromHome(activity: MainActivity, preset: LayoutPreset) {
        when (val r = app.layoutEngine.apply(preset, app.carStateRepository.state, Trigger.USER)) {
            is LayoutEngine.Result.Applied -> {
                app.layoutRepository.lastAppliedPresetId = preset.id
                activity.toast("Applied ${preset.title}")
            }
            is LayoutEngine.Result.Refused -> activity.toast("Refused: ${r.reason}")
            is LayoutEngine.Result.Invalid -> activity.toast("Invalid: ${r.errors.firstOrNull()}")
        }
    }

}

// -------------------------------------------------------------------- Layouts

class LayoutsSection(private val app: WitsCompanionApp) : MainActivity.Section {
    override val title = "Layouts"

    private lateinit var geometryLabel: TextView
    private lateinit var swapButton: Button

    override fun onCreateView(activity: MainActivity): View {
        val c = activity.column()

        // One coherent creator: pick two apps, set the proportion, then Apply or Save.
        c.addView(activity.heading("New layout"))
        c.addView(buildLayoutControls(activity))

        // The editable list, as clean cards with a Delete on the ones you added.
        c.addView(activity.heading("Your layouts"))
        app.layoutRepository.allPresets()
            .filter { it.kind == PresetKind.TILED && it.windows.size >= 2 }
            .forEach { c.addView(presetCard(activity, it)) }

        return activity.scroll(c)
    }

    /** Two app pickers, the proportion slider, and Apply / Save — the whole creator. */
    private fun buildLayoutControls(activity: MainActivity): View {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val repo = app.layoutRepository
        val catalog = app.appCatalog
        val suggested = catalog.suggestedPackages()
        if (suggested.size < 2) {
            box.addView(activity.body("Need at least two launchable apps to build a layout."))
            return box
        }
        val labels = suggested.map { catalog.labelFor(it) }
        val defaults = catalog.vendorDefaults()

        fun spinner(initial: Int) = android.widget.Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(
                activity, android.R.layout.simple_spinner_dropdown_item, labels
            )
            setSelection(initial.coerceIn(0, labels.lastIndex))
        }
        val leftInit = defaults.navigation?.let { suggested.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val rightInit = defaults.music?.let { suggested.indexOf(it) }?.takeIf { it >= 0 }
            ?: (if (suggested.size > 1) 1 else 0)

        val left = spinner(leftInit)
        val right = spinner(rightInit)
        box.addView(activity.body("Left app"))
        box.addView(left)
        box.addView(activity.body("Right app"))
        box.addView(right)

        // Proportion, inline. Persisted only on release; the label updates live.
        geometryLabel = activity.body("", mono = true).apply { setPadding(0, activity.dp(10), 0, 0) }
        box.addView(geometryLabel)
        box.addView(android.widget.SeekBar(activity).apply {
            max = SPLIT_STEPS
            progress = splitToProgress(repo.split)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) =
                    updateGeometryLabel(progressToSplit(p))
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    repo.split = progressToSplit(sb.progress)
                }
            })
        })
        swapButton = activity.button("") {
            repo.swapped = !repo.swapped
            updateGeometryLabel(repo.split)
        }
        box.addView(swapButton)
        updateGeometryLabel(repo.split)

        fun chosen(): Pair<String, String> =
            suggested[left.selectedItemPosition] to suggested[right.selectedItemPosition]
        fun preset() = chosen().let { (l, r) ->
            DefaultPresets.tiledFor(l, r, catalog.labelFor(l), catalog.labelFor(r))
        }

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(activity).apply {
            text = "Apply"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val (l, r) = chosen()
                if (l == r) activity.toast("Pick two different apps") else applyPreset(activity, preset())
            }
        })
        row.addView(Button(activity).apply {
            text = "Save"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val (l, r) = chosen()
                if (l == r) { activity.toast("Pick two different apps"); return@setOnClickListener }
                app.layoutRepository.saveCustomPreset(preset())
                activity.toast("Saved"); activity.refreshCurrentSection()
            }
        })
        box.addView(row)
        return box
    }

    /** A saved layout as a card: icons + title + ratio, tap to apply, Delete if custom. */
    private fun presetCard(activity: MainActivity, preset: LayoutPreset): View {
        val installed = preset.windows.all { app.windowController.isLaunchable(it.packageName) }
        val ratio = preset.splitFraction()?.let { "${(it * 100).toInt()}/${100 - (it * 100).toInt()}" }
        val title = preset.windows.sortedBy { it.bounds.left }.joinToString("  +  ") {
            if (it.packageName == WitsPackages.SELF) "Panel" else app.appCatalog.labelFor(it.packageName)
        }
        val card = activity.launchTile(
            title = title,
            subtitle = if (!installed) "not installed" else ratio,
            packages = preset.windows.map { it.packageName },
        ) { applyPreset(activity, preset) }

        if (app.layoutRepository.customPresets().any { it.id == preset.id }) {
            (card as LinearLayout).addView(Button(activity).apply {
                text = "Delete"; isAllCaps = false; textSize = 12f
                setOnClickListener {
                    app.layoutRepository.deleteCustomPreset(preset.id)
                    activity.toast("Deleted"); activity.refreshCurrentSection()
                }
            })
        }
        return card
    }

    // ------------------------------------------------------------------ geometry

    private fun updateGeometryLabel(split: Float) {
        if (!::geometryLabel.isInitialized) return
        val left = (split * 100).toInt()
        val swapped = app.layoutRepository.swapped
        geometryLabel.text = if (swapped) {
            "${100 - left} / $left   (primary app on the right)"
        } else {
            "$left / ${100 - left}   (primary app on the left)"
        }
        if (::swapButton.isInitialized) {
            swapButton.text = if (swapped) "⇄ primary on the right" else "⇄ primary on the left"
        }
    }

    /** The single apply path, so every entry point gets the same guards and feedback. */
    private fun applyPreset(activity: MainActivity, preset: LayoutPreset) {
        when (val result = app.layoutEngine.apply(
            preset, app.carStateRepository.state, Trigger.USER
        )) {
            is LayoutEngine.Result.Applied -> {
                app.layoutRepository.lastAppliedPresetId = preset.id
                activity.toast(
                    "Applied ${result.windows} window(s)" +
                        if (result.warnings.isEmpty()) "" else "; ${result.warnings.size} warning(s)"
                )
            }
            is LayoutEngine.Result.Refused -> activity.toast("Refused: ${result.reason}")
            is LayoutEngine.Result.Invalid -> activity.toast("Invalid: ${result.errors.joinToString()}")
        }
    }

    private fun fmt(v: Float) = String.format("%.2f", v)

    private companion object {
        /** One step per percent between MIN_SPLIT and MAX_SPLIT. */
        val SPLIT_STEPS =
            ((LayoutPreset.MAX_SPLIT - LayoutPreset.MIN_SPLIT) * 100).toInt()

        fun progressToSplit(progress: Int): Float =
            LayoutPreset.MIN_SPLIT + progress / 100f

        fun splitToProgress(split: Float): Int =
            ((split - LayoutPreset.MIN_SPLIT) * 100).toInt().coerceIn(0, SPLIT_STEPS)
    }
}

// ------------------------------------------------------------------ Car state

class CarStateSection(private val app: WitsCompanionApp) : MainActivity.Section {
    override val title = "Car"

    private lateinit var text: TextView

    override fun onCreateView(activity: MainActivity): View {
        val c = activity.column()
        c.addView(activity.heading("Signals"))
        c.addView(activity.body(
            "Values with unproven units are shown raw. A dash means the signal has " +
                "never been received — it is never displayed as zero."
        ))
        text = activity.body("—", mono = true)
        c.addView(text)
        return activity.scroll(c)
    }

    override fun onCarState(state: CarState) {
        if (!::text.isInitialized) return
        text.text = buildString {
            fun row(name: String, v: io.github.miklergm.witscompanion.carstate.SignalValue<*>) {
                appendLine(
                    "%-14s %-10s %-10s %s".format(
                        name, v.display(), v.availability.name.lowercase(),
                        v.ageMs()?.let { "${it / 1000}s" } ?: "-")
                )
            }
            appendLine("%-14s %-10s %-10s %s".format("signal", "value", "state", "age"))
            appendLine("-".repeat(46))
            row("acc", state.acc)
            row("reverse", state.reverse)
            row("brake", state.brake)
            row("illumination", state.illumination)
            row("source", state.source)
            row("battery", state.batteryVoltageRaw)
            row("speed", state.speedRaw)
            row("rpm", state.rpmRaw)
            row("steering", state.steeringAngleRaw)
            row("top package", state.topPackage)
            appendLine()
            appendLine("doors raw  " + state.doorsRaw.display() + "   (bitmask, undecoded)")
            appendLine()

            // PDC decode. The point of showing front and rear separately is to answer,
            // by watching it while parking forward, whether can.radar carries front data
            // at all outside reverse — the prerequisite for any forward-parking display.
            val radar = io.github.miklergm.witscompanion.carstate.RadarReading.parse(
                state.radarRaw.takeIf { it.isKnown }?.value
            )
            appendLine("PDC (can.radar = ${state.radarRaw.display()})")
            if (radar == null) {
                appendLine("  no reading")
            } else {
                val fl = io.github.miklergm.witscompanion.carstate.RadarReading.FRONT_LABELS
                val rl = io.github.miklergm.witscompanion.carstate.RadarReading.REAR_LABELS
                appendLine("  front " + fl.zip(radar.front).joinToString(" ") {
                    "${it.first}=${it.second ?: "-"}"
                } + (if (radar.anyFrontActive) "   <- ACTIVE" else ""))
                appendLine("  rear  " + rl.zip(radar.rear).joinToString(" ") {
                    "${it.first}=${it.second ?: "-"}"
                } + (if (radar.anyRearActive) "   <- ACTIVE" else ""))
                appendLine("  (0 = clear; larger = closer. Watch 'front' while parking forward.)")
            }
            appendLine()
            appendLine("mcu        ${state.mcuVersion.display()}")
            appendLine("product id ${state.productId.display()}")
            appendLine("build      ${state.buildDisplayId.display()}")
            if (state.simulated) appendLine("\n[SIMULATION MODE — no real vehicle data]")
        }
    }
}

// ------------------------------------------------------------------- Settings

class SettingsSection(private val app: WitsCompanionApp) : MainActivity.Section {
    override val title = "Settings"

    private lateinit var status: TextView

    override fun onCreateView(activity: MainActivity): View {
        val c = activity.column()
        val repo = app.layoutRepository

        // -------------------------------------------------- automatic restore
        c.addView(activity.heading("Automatic restore (opt-in)"))
        c.addView(activity.check("Re-apply the last layout when the app resumes", repo.restoreOnResume) {
            repo.restoreOnResume = it
        })
        c.addView(activity.check("Re-apply on ACC on", repo.restoreOnAcc) { repo.restoreOnAcc = it })
        c.addView(activity.check("Re-apply when Android source is confirmed", repo.restoreOnAndroidSource) {
            repo.restoreOnAndroidSource = it
        })
        c.addView(activity.check("Re-apply after reverse ends", repo.restoreAfterReverse) {
            repo.restoreAfterReverse = it
        })
        c.addView(activity.check("Re-apply after boot (30 s delay)", repo.restoreOnBoot) {
            repo.restoreOnBoot = it
        })
        c.addView(activity.check("Auto-start the Cockpit on boot & ACC", repo.autostartPanel) {
            repo.autostartPanel = it
        })
        c.addView(activity.check("Re-enable the hotspot when it was on before", repo.restoreHotspot) {
            repo.restoreHotspot = it
        })
        c.addView(activity.body(
            "Automatic triggers are refused while reverse is active or unknown, and never " +
                "switch the video source. Restore reasserts the last layout without " +
                "relaunching apps that are still running, so an active Maps route survives a " +
                "deep-sleep wake."
        ))

        // ------------------------------------------------------------ reset
        c.addView(activity.heading("Reset"))
        c.addView(activity.body(
            "The companion never modifies the system: a reboot always clears layouts, and " +
                "uninstalling removes everything, including any elevated permission."
        ))
        c.addView(activity.button("Reset layout — tiles back, show launcher") {
            app.layoutEngine.resetToVendorState()
            activity.toast("Returned to the vendor launcher")
        })
        c.addView(activity.button("Clear all app settings…") {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Clear all app settings?")
                .setMessage(
                    "Erases saved presets, the split ratio, side order and every toggle. " +
                        "Windows on screen are not touched. This cannot be undone."
                )
                .setPositiveButton("Clear") { _, _ ->
                    app.layoutRepository.clearAll()
                    activity.toast("Settings cleared")
                    activity.refreshCurrentSection()
                }
                .setNegativeButton("Cancel", null)
                .show()
        })

        c.addView(activity.heading("Day / night mode"))
        c.addView(activity.body(
            "Writes Settings.System/wits_night_mode only. It never changes screen " +
                "brightness or MCU dimming — those are separate mechanisms."
        ))

        status = activity.body("—", mono = true)
        c.addView(status)

        WitsNightModeController.Mode.entries.forEach { mode ->
            c.addView(activity.button("${mode.label} (${mode.value}) — ${mode.description}") {
                if (app.layoutRepository.nightModeBackup == null) {
                    app.layoutRepository.nightModeBackup = app.nightModeController.readRaw() ?: "unset"
                }
                when (val r = app.nightModeController.write(mode)) {
                    WitsNightModeController.Result.Written -> activity.toast("Set to ${mode.label}")
                    WitsNightModeController.Result.PermissionRequired -> {
                        activity.toast("Grant \"Modify system settings\" first")
                        activity.startActivity(app.nightModeController.permissionIntent())
                    }
                    is WitsNightModeController.Result.Refused -> activity.toast("Refused: ${r.reason}")
                    is WitsNightModeController.Result.Error -> activity.toast("Error: ${r.message}")
                }
                refresh()
            })
        }

        c.addView(activity.button("Restore previous value") {
            val backup = app.layoutRepository.nightModeBackup
            if (backup == null || backup == "unset") {
                activity.toast("No previous value recorded")
            } else {
                app.nightModeController.restoreRaw(backup)
                activity.toast("Restored $backup")
                refresh()
            }
        })

        c.addView(activity.button("Grant \"Modify system settings\"") {
            activity.startActivity(app.nightModeController.permissionIntent())
        })

        refresh()
        return activity.scroll(c)
    }

    override fun onResume() = refresh()

    private fun refresh() {
        if (!::status.isInitialized) return
        val ctl = app.nightModeController
        status.text = buildString {
            appendLine("WRITE_SETTINGS granted : ${ctl.canWrite()}")
            appendLine("wits_night_mode (raw)  : ${ctl.readRaw() ?: "unset"}")
            appendLine("interpreted            : ${ctl.readMode()?.label ?: "unset/unknown"}")
            appendLine("backup for undo        : ${app.layoutRepository.nightModeBackup ?: "none"}")
            appendLine()
            ctl.readContext().forEach { (k, v) -> appendLine("%-24s : %s".format(k, v ?: "unset")) }
        }
    }
}

// ---------------------------------------------------------------------- Debug

class DebugSection(private val app: WitsCompanionApp) : MainActivity.Section {
    override val title = "Debug"

    private lateinit var text: TextView

    override fun onCreateView(activity: MainActivity): View {
        val c = activity.column()
        c.addView(activity.heading("Capabilities"))
        text = activity.body("—", mono = true)
        c.addView(text)

        c.addView(activity.check("Simulation mode (no vendor broadcasts)",
            app.layoutRepository.simulationEnabled) { on ->
            app.layoutRepository.simulationEnabled = on
            app.carStateRepository.setSimulationEnabled(on)
            activity.toast(if (on) "Simulation ON" else "Simulation OFF")
            refresh()
        })

        c.addView(activity.button("Re-probe property access") {
            app.propertyReader.reprobe(); refresh()
            activity.toast("Strategy: ${app.propertyReader.activeStrategy}")
        })

        c.addView(activity.button("Export diagnostic log") {
            (activity as? MainActivity)?.let { a ->
                LogExportHelper.export(a, app)
            }
        })
        c.addView(activity.button("Clear diagnostic log") {
            app.eventLogger.clear(); activity.toast("Cleared"); refresh()
        })
        c.addView(activity.body(
            "The log is local only — this app has no INTERNET permission. " +
                "VIN, serials, MAC/Bluetooth addresses, Wi-Fi credentials and media " +
                "titles are redacted before writing."
        ))
        refresh()
        return activity.scroll(c)
    }

    override fun onResume() = refresh()

    private fun refresh() {
        if (!::text.isInitialized) return
        val wc = app.windowController
        val ctx = text.context
        val full = wc.fullDisplayArea(ctx)
        val usable = wc.usableArea(ctx)
        // Our own window, which is NOT what layouts are measured from: the companion can
        // be one of the tiles it places, and a shrinking window is the visible symptom of
        // measuring a layout from it. Shown so that case is obvious at a glance.
        val own = runCatching {
            android.graphics.Rect(
                ctx.getSystemService(android.view.WindowManager::class.java)
                    .currentWindowMetrics.bounds
            )
        }.getOrNull()
        text.text = buildString {
            appendLine("display full   ${full.width()}x${full.height()}  $full")
            appendLine("display usable ${usable.width()}x${usable.height()}  $usable")
            appendLine("insets         l=${usable.left - full.left} t=${usable.top - full.top} " +
                "r=${full.right - usable.right} b=${full.bottom - usable.bottom}")
            if (own != null) {
                val narrow = own.width() * 2 < full.width()
                appendLine("our own window ${own.width()}x${own.height()}" +
                    if (narrow) "  <- we are a tile; layouts still use the display" else "")
            }
            appendLine()
            val wc = app.windowController
            appendLine("window path    : ${if (wc.isPrivileged) "PRIVILEGED (resizeTask, no flicker)" else "vendor CHANGE_WINDOW hook"}")
            if (wc.isPrivileged) {
                val tasks = wc.rootTasks().filter { it.packageName != null }
                appendLine("root tasks     : ${tasks.size}")
                tasks.take(6).forEach {
                    appendLine("  #${it.taskId} ${it.packageName?.substringAfterLast('.')} " +
                        "mode=${WitsWindowMode.name(it.windowingMode)} vis=${it.visible}")
                }
            }
            appendLine("property strategy : ${app.propertyReader.activeStrategy}")
            appendLine("diagnostics       : ${app.propertyReader.diagnostics}")
            appendLine()
            appendLine("Maps installed    : ${wc.isLaunchable(WitsPackages.MAPS)}")
            appendLine("Spotify installed : ${wc.isLaunchable(WitsPackages.SPOTIFY)}")
            appendLine("Launcher present  : ${wc.isLaunchable(WitsPackages.WITS_LAUNCHER)}")
            appendLine()
            appendLine("notification access: ${app.mediaRepository.isPermissionGranted()}")
            appendLine("WRITE_SETTINGS     : ${app.nightModeController.canWrite()}")
            appendLine()
            appendLine("freeform (global)  : ${readGlobal(ctx, WitsSettingsKeys.ENABLE_FREEFORM_SUPPORT)}")
            appendLine("force resizable    : ${readGlobal(ctx, WitsSettingsKeys.FORCE_RESIZABLE_ACTIVITIES)}")
            appendLine("PiP feature        : ${hasPip(ctx)}")
            appendLine()
            appendLine("log lines          : ${app.eventLogger.lineCount()}")
            appendLine("simulation         : ${app.carStateRepository.simulationEnabled}")
        }
    }

    private fun readGlobal(ctx: Context, key: String): String =
        runCatching {
            android.provider.Settings.Global.getString(ctx.contentResolver, key) ?: "unset"
        }.getOrDefault("error")

    private fun hasPip(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
}
