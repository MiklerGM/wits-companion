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

// ------------------------------------------------------------------ Dashboard

class DashboardSection(private val app: WitsCompanionApp) : MainActivity.Section {
    override val title = "Dashboard"

    private lateinit var mediaText: TextView
    private lateinit var carText: TextView
    private var activity: MainActivity? = null

    private val mediaListener = io.github.miklergm.witscompanion.media.MediaSessionRepository
        .Listener { snapshot -> renderMedia(snapshot) }

    override fun onCreateView(activity: MainActivity): View {
        this.activity = activity
        val c = activity.column()

        c.addView(activity.heading("Media"))
        mediaText = activity.body("—")
        c.addView(mediaText)

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "◀◀" to { app.mediaRepository.previous(); Unit },
            "▶ / ❚❚" to { app.mediaRepository.playPause() },
            "▶▶" to { app.mediaRepository.next(); Unit },
        ).forEach { (label, action) ->
            row.addView(Button(activity).apply {
                text = label; isAllCaps = false
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        c.addView(row)

        c.addView(activity.button("Grant notification access (for media)") {
            if (app.mediaRepository.isPermissionGranted()) {
                activity.toast("Already granted")
            } else {
                activity.startActivity(app.mediaRepository.permissionIntent())
            }
        })
        c.addView(activity.button("Open player") {
            app.mediaRepository.openPlayer()?.let { activity.startActivity(it) }
                ?: activity.toast("Player not installed")
        })

        c.addView(activity.heading("Car"))
        carText = activity.body("—", mono = true)
        c.addView(carText)

        c.addView(activity.heading("Source"))
        c.addView(activity.button("Open OEM BMW") { switchSource(activity, oem = true) })
        c.addView(activity.button("Return to Android") { switchSource(activity, oem = false) })
        c.addView(activity.body(
            "Source switching is always manual. It is refused while the reverse " +
                "camera is active or its state is unknown."
        ))

        c.addView(activity.heading("Layout"))
        c.addView(activity.button("Restore last layout") {
            when (val r = app.recoveryCoordinator.restoreNow(app.carStateRepository.state)) {
                is LayoutEngine.Result.Applied -> activity.toast("Applied ${r.windows} window(s)")
                is LayoutEngine.Result.Refused -> activity.toast("Refused: ${r.reason}")
                is LayoutEngine.Result.Invalid -> activity.toast("Invalid: ${r.errors.firstOrNull()}")
            }
        })

        return activity.scroll(c)
    }

    private fun switchSource(activity: MainActivity, oem: Boolean) {
        val state = app.carStateRepository.state
        val result = if (oem) app.sourceController.switchToOem(state)
        else app.sourceController.switchToAndroid(state)
        activity.toast(
            when (result) {
                WitsSourceController.Result.Sent ->
                    "Request sent — waiting for the head unit to confirm"
                is WitsSourceController.Result.Refused -> "Refused: ${result.reason}"
                is WitsSourceController.Result.Error -> "Error: ${result.message}"
            }
        )
    }

    override fun onResume() {
        app.mediaRepository.start()
        app.mediaRepository.addListener(mediaListener)
        app.mediaRepository.refresh()
    }

    override fun onPause() {
        app.mediaRepository.removeListener(mediaListener)
    }

    private fun renderMedia(s: MediaSnapshot) {
        mediaText.text = when {
            !s.permissionGranted ->
                "Notification access not granted.\nThe media panel needs it to read MediaSession."
            !s.available -> "No player is running.\nOpen Spotify to see controls here."
            else -> buildString {
                appendLine(s.title ?: "(no title)")
                appendLine(s.artist ?: "(no artist)")
                append(if (s.isPlaying) "playing" else "paused")
                s.packageName?.let { append("  ·  $it") }
            }
        }
    }

    override fun onCarState(state: CarState) {
        if (!::carText.isInitialized) return
        carText.text = buildString {
            appendLine("source      ${state.sourceName}")
            appendLine("ACC         ${state.acc.display()}")
            appendLine("reverse     ${state.reverseActive?.toString() ?: "unknown"}")
            appendLine("illumination ${state.illumination.display()}")
            appendLine("battery raw ${state.batteryVoltageRaw.display()}   (units unproven)")
            appendLine("speed raw   ${state.speedRaw.display()}   (units unproven)")
            if (state.simulated) append("\n[SIMULATION MODE]")
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
        val repo = app.layoutRepository

        // ------------------------------------------------------------- geometry
        // One control for the proportion, shared by every combination of apps. Nothing
        // here re-inflates the section: rebuilding it threw the ScrollView back to the
        // top on every tap, which made adjusting the ratio needlessly annoying.
        c.addView(activity.heading("Proportion"))
        geometryLabel = activity.body("", mono = true)
        c.addView(geometryLabel)

        c.addView(android.widget.SeekBar(activity).apply {
            max = SPLIT_STEPS
            progress = splitToProgress(repo.split)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    // Live feedback while dragging; persisted only when the user lets go.
                    updateGeometryLabel(progressToSplit(p))
                }

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
        c.addView(swapButton)
        updateGeometryLabel(repo.split)

        c.addView(activity.body(
            "The proportion applies to whichever combination you pick below. " +
                "Changing it does not move any window until you apply a combination."
        ))

        // --------------------------------------------------------- combinations
        c.addView(activity.heading("Apps"))
        app.layoutRepository.allPresets().forEach { preset ->
            c.addView(presetRow(activity, preset))
        }

        c.addView(activity.heading("Mode B — anchor panel"))
        c.addView(activity.body(
            "Keeps the companion fullscreen underneath and floats one app (the map) over " +
                "it. Media, volume readouts and source are drawn by the panel itself " +
                "instead of getting windows of their own."
        ))
        // Applying the anchored preset is the whole of Mode B: it brings the panel to the
        // front AND places the map over it. Opening the panel alone left the previous
        // layout untouched, which read as the button doing nothing.
        DefaultPresets.ID_MAPS_ANCHORED.let { id ->
            val anchored = app.layoutRepository.preset(id)
            if (anchored != null) {
                c.addView(activity.button("Start Mode B — panel + map over it") {
                    applyPreset(activity, anchored)
                })
            }
        }
        c.addView(activity.button("Open panel only (does not move any window)") {
            activity.startActivity(android.content.Intent(activity, DashboardActivity::class.java))
        })

        c.addView(activity.heading("Automatic restore (opt-in)"))
        c.addView(activity.check("Re-apply when the app resumes", repo.restoreOnResume) {
            repo.restoreOnResume = it
        })
        c.addView(activity.check("Re-apply on ACC on", repo.restoreOnAcc) {
            repo.restoreOnAcc = it
        })
        c.addView(activity.check("Re-apply when Android source is confirmed", repo.restoreOnAndroidSource) {
            repo.restoreOnAndroidSource = it
        })
        c.addView(activity.check("Re-apply after reverse ends", repo.restoreAfterReverse) {
            repo.restoreAfterReverse = it
        })
        c.addView(activity.check("Re-apply after boot (30 s delay)", repo.restoreOnBoot) {
            repo.restoreOnBoot = it
        })
        c.addView(activity.body(
            "All automatic triggers are refused while reverse is active or unknown. " +
                "None of them ever switches the video source."
        ))
        return activity.scroll(c)
    }

    private fun presetRow(activity: MainActivity, preset: LayoutPreset): View {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(8), 0, activity.dp(8))
        }
        val issues = LayoutValidator.validate(preset)
        val missing = preset.windows.filterNot { app.windowController.isLaunchable(it.packageName) }

        val kindTag = when (preset.kind) {
            io.github.miklergm.witscompanion.layout.PresetKind.ANCHORED -> "  [anchor]"
            else -> ""
        }
        box.addView(activity.body(
            preset.title + kindTag + if (preset.experimental) "  [experimental]" else ""
        ))
        box.addView(activity.body(
            preset.windows.joinToString("\n") { w ->
                "  ${w.packageName}  ${fmt(w.bounds.left)},${fmt(w.bounds.top)}–${fmt(w.bounds.right)},${fmt(w.bounds.bottom)}"
            },
            mono = true,
        ))
        if (missing.isNotEmpty()) {
            box.addView(activity.body("  missing: ${missing.joinToString { it.packageName }}").apply {
                setTextColor(Color.RED)
            })
        }
        issues.forEach { box.addView(activity.body("  ! ${it.message}")) }

        box.addView(activity.button("Apply \"${preset.title}\"") { applyPreset(activity, preset) })
        return box
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
            appendLine("radar raw  " + state.radarRaw.display() + "   (packed, undecoded)")
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
    override val title = "Day/Night"

    private lateinit var status: TextView

    override fun onCreateView(activity: MainActivity): View {
        val c = activity.column()
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
