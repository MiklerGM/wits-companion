package io.github.miklergm.witscompanion.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.carstate.PropertyReader
import io.github.miklergm.witscompanion.layout.CockpitLeft
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
import io.github.miklergm.witscompanion.wits.TaskObservation
import io.github.miklergm.witscompanion.wits.WitsWindowMode
import io.github.miklergm.witscompanion.wits.currentWindowBounds

// ------------------------------------------------------------------ view helpers

private fun Context.column(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(12), dp(16), dp(24))
}

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * Sizes for the configuration screens.
 *
 * These are read at arm's length in a car, often while the vehicle is moving, and the screens
 * started life as a debugging surface: 14sp body text, default-height buttons and bare
 * checkboxes. Everything below is sized for a glance and a thumb rather than a cursor.
 */
private object ConfigMetrics {
    /** Nothing tappable is shorter than this. */
    const val TOUCH_DP = 56

    /** A settings row carrying a title and a line of explanation. */
    const val ROW_DP = 72
    const val BODY_SP = 16f
    const val MONO_SP = 13f
    const val HEADING_SP = 20f
    const val BUTTON_SP = 16f
    const val SUBTITLE_SP = 13f
}

private fun Context.heading(text: String) = TextView(this).apply {
    this.text = text
    textSize = ConfigMetrics.HEADING_SP
    setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(20), 0, dp(8))
}

private fun Context.body(text: String, mono: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = if (mono) ConfigMetrics.MONO_SP else ConfigMetrics.BODY_SP
    if (mono) typeface = Typeface.MONOSPACE
    setPadding(0, dp(3), 0, dp(3))
}

private fun Context.button(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = ConfigMetrics.BUTTON_SP
    minHeight = dp(ConfigMetrics.TOUCH_DP)
    minimumHeight = dp(ConfigMetrics.TOUCH_DP)
    setOnClickListener { onClick() }
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }
}

/**
 * A settings toggle as a full-width row: what it does, one line of why, and a switch.
 *
 * Replaces a bare [CheckBox], whose tappable area was the label's own height and whose box is
 * a few millimetres across on this display. The whole row toggles, so the target is the width
 * of the screen and [ConfigMetrics.ROW_DP] tall.
 *
 * [subtitle] is not decoration. Several of these decide whether the app moves windows on its
 * own, and the screen previously offered no way to find out what any individual one did short
 * of reading a paragraph underneath the group.
 */
private fun Context.switchRow(
    title: String,
    subtitle: String,
    initial: Boolean,
    onChange: (Boolean) -> Unit,
): View {
    val toggle = SwitchMaterial(this).apply {
        isChecked = initial
        isClickable = false          // the row owns the click
        isFocusable = false
    }
    val labels = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@switchRow).apply {
            text = title
            textSize = ConfigMetrics.BODY_SP
        })
        addView(TextView(this@switchRow).apply {
            text = subtitle
            textSize = ConfigMetrics.SUBTITLE_SP
            setTextColor(attrColor(android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(0, dp(2), 0, 0)
        })
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(ConfigMetrics.ROW_DP)
        setPadding(dp(4), dp(10), dp(4), dp(10))
        background = roundedRipple(
            Color.TRANSPARENT, dp(12).toFloat(),
            attrColor(com.google.android.material.R.attr.colorControlHighlight, Color.LTGRAY),
        )
        addView(labels)
        addView(toggle)
        isClickable = true
        setOnClickListener {
            toggle.isChecked = !toggle.isChecked
            onChange(toggle.isChecked)
        }
    }
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

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val cards = mutableListOf<View>()

    /**
     * True while a layout is being placed. Overlapping applies (rapid taps) supersede each
     * other mid-sequence and could leave an app freeform-but-invisible, so the cards are
     * locked for the duration of the placement rather than churning.
     */
    @Volatile
    private var locked = false

    override fun onCreateView(activity: MainActivity): View {
        val c = activity.column()
        val repo = app.layoutRepository
        cards.clear()
        locked = false

        // Cockpit — the primary, accented card, spanning the full width.
        val cockpit = activity.launchTile(
            title = "Cockpit",
            subtitle = "map + panel",
            packages = listOfNotNull(cockpitFloatPackage()),
            primary = true,
        ) { if (!locked) openCockpit(activity) }
        cards += cockpit
        c.addView(cockpit)

        // Side-by-side layouts flow into as many columns as the width allows: a grid on the
        // wide head-unit display, a single column on a narrow one.
        val grid = FlowLayout(activity).apply {
            hGap = activity.dp(10); vGap = activity.dp(10)
            setPadding(0, activity.dp(10), 0, 0)
        }
        val tileWidth = activity.dp(360)
        repo.allPresets().filter { it.windows.size >= 2 && it.kind == PresetKind.TILED }.forEach { preset ->
            val installed = preset.windows.all { app.windowController.isLaunchable(it.packageName) }
            val ratio = preset.splitFraction()
            ?.let { LayoutPreset.splitPercent(it) }
            ?.let { "$it/${100 - it}" }
            val card = activity.launchTile(
                title = tileTitle(preset),
                subtitle = if (!installed) "not installed" else ratio,
                packages = preset.windows.map { it.packageName },
            ) { if (!locked) applyFromHome(activity, preset) }
            // Fixed width so the flow can grid them; height wraps content.
            card.layoutParams = ViewGroup.MarginLayoutParams(tileWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            cards += card
            grid.addView(card)
        }
        c.addView(grid)

        // Reset: one tap from the landing screen to return everything to the vendor
        // launcher. Neutral card so it does not compete with the layout tiles.
        val reset = activity.launchTile(
            title = "Reset",
            subtitle = "close the tiles, show the launcher",
            packages = emptyList(),
        ) {
            if (!locked) {
                app.layoutEngine.resetToVendorState()
                activity.toast("Returned to the vendor launcher")
            }
        }
        c.addView(reset)
        return activity.scroll(c)
    }

    /** Blocks further taps and dims the cards while a layout is being placed. */
    private fun lockWhilePlacing() {
        locked = true
        cards.forEach { it.alpha = 0.5f }
        ui.postDelayed({
            locked = false
            cards.forEach { it.alpha = 1f }
        }, PLACING_LOCK_MS)
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
        lockWhilePlacing()
        val anchored = app.layoutRepository.preset(DefaultPresets.ID_MAPS_ANCHORED)
        if (anchored != null) {
            // Applying the anchored preset also brings the Cockpit panel up — as a freeform
            // tile beside the map (LayoutEngine.bringAnchorToFront). We deliberately do NOT
            // start DashboardActivity here as well: a plain start would create it fullscreen
            // first, and the later freeform placement would only reorder that fullscreen task,
            // leaving the panel overlapping (and hiding) the map on the first control tap.
            when (val r = app.layoutEngine.apply(anchored, app.carStateRepository.state, Trigger.USER)) {
                is LayoutEngine.Result.Applied -> {
                    app.layoutRepository.lastAppliedPresetId = anchored.id
                    val mapPkg = anchored.windows
                        .firstOrNull { it.packageName != WitsPackages.SELF }?.packageName
                    app.layoutRepository.cockpitLeft =
                        mapPkg?.let { CockpitLeft.App(it) } ?: CockpitLeft.Default
                    dismissConfig(activity)
                }
                // A refusal is usually the reverse guard. Recording the Cockpit as applied and
                // closing the screen anyway told the user it had worked and left the persisted
                // last-layout pointing at a layout that was never placed.
                is LayoutEngine.Result.Refused -> activity.toast("Refused: ${r.reason}")
                is LayoutEngine.Result.Invalid -> activity.toast("Invalid: ${r.errors.joinToString()}")
            }
        } else {
            // No anchored preset to place the map with — still open the panel.
            activity.startActivity(android.content.Intent(activity, DashboardActivity::class.java))
        }
    }

    /**
     * Gets this config screen out of the way after a layout is applied, so it does not cover the
     * windows just placed. `removeTask` cannot clear the very task that triggered the apply (it is
     * the resumed activity — `[RUNTIME]` 2026-08-08: the config lingered over a fresh Maps+Chrome),
     * so MainActivity finishes itself; the gear reopens it when needed.
     */
    private fun dismissConfig(activity: MainActivity) = activity.finish()

    private fun applyFromHome(activity: MainActivity, preset: LayoutPreset) {
        lockWhilePlacing()
        when (val r = app.layoutEngine.apply(preset, app.carStateRepository.state, Trigger.USER)) {
            is LayoutEngine.Result.Applied -> {
                app.layoutRepository.lastAppliedPresetId = preset.id
                // A tiled layout leaves the Cockpit surface: reset the left-tile state so a later
                // autostart panel does not come up still "hidden" from a previous Cockpit session.
                app.layoutRepository.cockpitLeft = CockpitLeft.Default
                activity.toast("Applied ${preset.title}")
                dismissConfig(activity)
            }
            is LayoutEngine.Result.Refused -> activity.toast("Refused: ${r.reason}")
            is LayoutEngine.Result.Invalid -> activity.toast("Invalid: ${r.errors.firstOrNull()}")
        }
    }

    private companion object {
        /** Cards stay locked for the placement sequence (two phases + a small buffer). */
        const val PLACING_LOCK_MS = 1_800L
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

        // The editable list, as clean cards in the same adaptive grid as Home.
        c.addView(activity.heading("Your layouts"))
        val grid = FlowLayout(activity).apply {
            hGap = activity.dp(10); vGap = activity.dp(10)
            setPadding(0, activity.dp(6), 0, 0)
        }
        app.layoutRepository.allPresets()
            .filter { it.kind == PresetKind.TILED && it.windows.size >= 2 }
            .forEach { preset ->
                val card = presetCard(activity, preset).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        activity.dp(380), ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                grid.addView(card)
            }
        c.addView(grid)

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
        // The split control: a bar you can drag or turn, flanked by nudge buttons.
        //
        // A SeekBar alone is a poor control in a moving car — it wants fine motor precision for
        // a one-percent change — so ± buttons do the precise part and the bar does the coarse.
        // The bar is kept because the rotary controller drives it, which is the one input that
        // works without looking at the screen at all.
        val slider = android.widget.SeekBar(activity).apply {
            max = SPLIT_STEPS
            progress = splitToProgress(repo.split)
            minimumHeight = activity.dp(ConfigMetrics.TOUCH_DP)
            // A thumb big enough to find, and vertical padding so the whole strip is tappable
            // rather than just the track.
            thumb = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(activity.dp(28), activity.dp(28))
                setColor(activity.attrColor(com.google.android.material.R.attr.colorPrimary, Color.DKGRAY))
            }
            splitTrack = false
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                /**
                 * Whether a touch drag is in progress.
                 *
                 * The split used to be persisted in `onStopTrackingTouch` alone, which only ever
                 * fires for touch. A rotary controller — which this head unit has — a D-pad, a
                 * keyboard or an accessibility service all move the bar through
                 * `onProgressChanged` and never end a touch, so the label followed the user and
                 * the setting silently did not. Apply then used the old ratio.
                 *
                 * A discrete change is written as it happens; a drag is written once at the end,
                 * so a swipe across the bar is one preference write rather than fifty.
                 */
                private var dragging = false

                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                    dragging = true
                }

                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    val split = progressToSplit(p)
                    updateGeometryLabel(split)
                    // A SeekBar announces its own progress — here 40 of 55, which a screen
                    // reader renders as 73%. The number that means anything is the split the
                    // label shows, so say that instead.
                    val left = LayoutPreset.splitPercent(split)
                    sb.contentDescription = "Split $left / ${100 - left}"
                    if (fromUser && !dragging) repo.split = split
                }

                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    dragging = false
                    repo.split = progressToSplit(sb.progress)
                }
            })
            LayoutPreset.splitPercent(repo.split).let { contentDescription = "Split $it / ${100 - it}" }
        }

        val sliderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            fun step(label: String, by: Int) = Button(activity).apply {
                text = label
                textSize = 20f
                isAllCaps = false
                minWidth = activity.dp(ConfigMetrics.TOUCH_DP)
                minimumWidth = activity.dp(ConfigMetrics.TOUCH_DP)
                minHeight = activity.dp(ConfigMetrics.TOUCH_DP)
                minimumHeight = activity.dp(ConfigMetrics.TOUCH_DP)
                contentDescription = if (by < 0) "Narrower" else "Wider"
                // setProgress drives the bar's listener, so persistence, the label and the
                // announcement all happen in one place rather than three.
                setOnClickListener {
                    slider.progress = (slider.progress + by).coerceIn(0, SPLIT_STEPS)
                    repo.split = progressToSplit(slider.progress)
                    updateGeometryLabel(repo.split)
                }
            }
            addView(step("−", -1))
            addView(slider)
            addView(step("+", 1))
        }
        box.addView(sliderRow)
        swapButton = activity.button("") {
            repo.swapped = !repo.swapped
            updateGeometryLabel(repo.split)
        }
        box.addView(swapButton)
        updateGeometryLabel(repo.split)

        fun chosen(): Pair<String, String> =
            suggested[left.selectedItemPosition] to suggested[right.selectedItemPosition]
        // The placeholder geometry DefaultPresets.tiledFor() carries is a stand-in; the split
        // slider and the swap button above only take effect once the repository decorates it.
        // Applying the raw preset ignored both controls entirely.
        fun rawPreset() = chosen().let { (l, r) ->
            DefaultPresets.tiledFor(l, r, catalog.labelFor(l), catalog.labelFor(r))
        }
        // Saved undecorated, so a later change to the split or side order retunes it too.
        fun preset() = repo.decorate(rawPreset())

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
                app.layoutRepository.saveCustomPreset(rawPreset())
                activity.toast("Saved"); activity.refreshCurrentSection()
            }
        })
        box.addView(row)
        return box
    }

    /** A saved layout as a card: icons + title + ratio, tap to apply, Delete if custom. */
    private fun presetCard(activity: MainActivity, preset: LayoutPreset): View {
        val installed = preset.windows.all { app.windowController.isLaunchable(it.packageName) }
        val ratio = preset.splitFraction()
            ?.let { LayoutPreset.splitPercent(it) }
            ?.let { "$it/${100 - it}" }
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
        val left = LayoutPreset.splitPercent(split)
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
                // A tiled layout leaves the Cockpit surface: reset the left-tile state so a later
                // autostart panel does not come up still "hidden" from a previous Cockpit session.
                app.layoutRepository.cockpitLeft = CockpitLeft.Default
                activity.toast(
                    "Applied ${result.windows} window(s)" +
                        if (result.warnings.isEmpty()) "" else "; ${result.warnings.size} warning(s)"
                )
                // Get the config out of the way so it does not cover the windows just placed
                // (removeTask can't clear the resumed activity that triggered the apply).
                activity.finish()
            }
            is LayoutEngine.Result.Refused -> activity.toast("Refused: ${result.reason}")
            is LayoutEngine.Result.Invalid -> activity.toast("Invalid: ${result.errors.joinToString()}")
        }
    }

    private fun fmt(v: Float) = String.format("%.2f", v)

    private companion object {
        // The scale itself lives on LayoutPreset, where it can be tested: truncating instead of
        // rounding here cost a percent every time the settings were opened.
        val SPLIT_STEPS = LayoutPreset.SPLIT_STEPS

        fun progressToSplit(progress: Int): Float = LayoutPreset.progressToSplit(progress)

        fun splitToProgress(split: Float): Int = LayoutPreset.splitToProgress(split)
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

        // ----------------------------------------------------------- autostart
        c.addView(activity.heading("Autostart (opt-in)"))
        c.addView(activity.switchRow(
            "Start on power-up",
            "Re-applies the last layout on ignition or a cold boot.",
            repo.autostartOnPower,
        ) { repo.autostartOnPower = it })
        c.addView(activity.switchRow(
            "Restore when the app is opened",
            "A tiled layout is skipped, so these settings stay reachable.",
            repo.restoreOnResume,
        ) { repo.restoreOnResume = it })
        c.addView(activity.switchRow(
            "Bring the hotspot back",
            "A short stop turns it off. Only ever switches it on, never off.",
            repo.restoreHotspot,
        ) { repo.restoreHotspot = it })
        c.addView(activity.body(
            "Autostart is refused while reverse is active — the head unit shows the camera itself — " +
                "and never switches the video source. An active Maps route survives a deep-sleep " +
                "wake: running apps are repositioned, not relaunched."
        ))

        // ------------------------------------------------------------- media
        c.addView(activity.heading("Media"))
        c.addView(activity.body(
            "The Cockpit's play/pause/next needs notification access to read the player.\n\n" +
                "It is also used to read the next turn from a navigation app's own " +
                "notification, so the Cockpit can show it beside the media controls. " +
                "Only ongoing navigation notifications from known map apps are read, " +
                "and the text is never written to the log or included in an export."
        ))
        c.addView(activity.button("Grant notification access") {
            when {
                app.mediaRepository.isPermissionGranted() -> activity.toast("Already granted")
                // Platform build: grant it ourselves, since the vendor menu is unreachable.
                app.mediaRepository.grantSelf() -> activity.toast("Granted")
                else -> activity.startActivity(app.mediaRepository.permissionIntent())
            }
            // Pick up the grant (self-grant or returning from the system menu) without a
            // restart, so the Cockpit's media controls come alive immediately.
            app.mediaRepository.ensureObserving()
        })

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
                "brightness or MCU dimming — those are separate mechanisms.\n\n" +
                "On this unit it may have no visible effect: the theme is locked on night " +
                "by the vendor (UiModeManager mNightModeLocked), and what actually changes " +
                "with the headlights is the backlight, not the theme."
        ))

        status = activity.body("—", mono = true)
        c.addView(status)

        WitsNightModeController.Mode.entries.forEach { mode ->
            c.addView(activity.button("${mode.label} (${mode.value}) — ${mode.description}") {
                if (app.layoutRepository.nightModeBackup == null) {
                    // "unset" is a real recorded state — the key genuinely had no value before
                    // we touched it — not an absence of backup. restoreRaw() tells the user
                    // that plainly rather than claiming nothing was recorded.
                    app.layoutRepository.nightModeBackup =
                        app.nightModeController.readRaw() ?: WitsNightModeController.UNSET
                }
                when (val r = app.nightModeController.write(mode)) {
                    WitsNightModeController.Result.Written -> activity.toast("Set to ${mode.label}")
                    WitsNightModeController.Result.PermissionRequired -> {
                        activity.toast("Grant \"Modify system settings\" first")
                        activity.startActivity(app.nightModeController.permissionIntent())
                    }
                    is WitsNightModeController.Result.Refused -> activity.toast("Refused: ${r.reason}")
                    is WitsNightModeController.Result.Error -> activity.toast("Error: ${r.message}")
                    is WitsNightModeController.Result.NotApplied ->
                        activity.toast("Not applied: ${r.reason}")
                }
                refresh()
            })
        }

        c.addView(activity.button("Restore previous value") {
            val backup = app.layoutRepository.nightModeBackup
            if (backup == null) {
                activity.toast("No previous value recorded")
            } else {
                // The old version toasted "Restored" unconditionally, including for the "unset"
                // backup that restoreRaw() cannot honour and for a write that never landed.
                when (val r = app.nightModeController.restoreRaw(backup)) {
                    WitsNightModeController.Result.Written -> activity.toast("Restored $backup")
                    WitsNightModeController.Result.PermissionRequired ->
                        activity.toast("Grant \"Modify system settings\" first")
                    is WitsNightModeController.Result.Refused -> activity.toast(r.reason)
                    is WitsNightModeController.Result.Error -> activity.toast("Error: ${r.message}")
                    is WitsNightModeController.Result.NotApplied ->
                        activity.toast("Not applied: ${r.reason}")
                }
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

        c.addView(activity.switchRow(
            "Simulation mode",
            "Fabricated vehicle data for testing. No layout is applied and no hotspot switched on.",
            app.layoutRepository.simulationEnabled,
        ) { on ->
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
        // currentWindowMetrics needs an Activity context; text.context is one here, but go
        // through the compat helper so the API-30 gate lives in exactly one place.
        val own = (ctx as? android.app.Activity)?.currentWindowBounds()
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
                // "could not read" and "read, nothing there" print differently on purpose —
                // this screen is where an observation failure gets noticed.
                when (val observation = wc.observeTasks()) {
                    is TaskObservation.Unavailable ->
                        appendLine("root tasks     : UNREADABLE (${observation.reason})")
                    is TaskObservation.Observed -> {
                        val tasks = observation.tasks.filter { it.packageName != null }
                        appendLine("root tasks     : ${tasks.size}")
                        tasks.take(6).forEach {
                            appendLine("  #${it.taskId} ${it.packageName?.substringAfterLast('.')} " +
                                "mode=${WitsWindowMode.name(it.windowingMode)} vis=${it.visible}")
                        }
                    }
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
            // What the current player offers beyond play/pause/next. This is how the answer to
            // "does Spotify expose a like action, and under what id" gets found — by reading a
            // real session rather than guessing at the id.
            val custom = app.mediaRepository.snapshot.customActions
            appendLine("player custom actions: ${custom.size}")
            custom.forEach { appendLine("   ${it.action}  —  ${it.name}") }
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
