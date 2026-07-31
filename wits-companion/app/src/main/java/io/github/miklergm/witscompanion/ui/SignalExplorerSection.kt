package io.github.miklergm.witscompanion.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import io.github.miklergm.witscompanion.carstate.CarState
import io.github.miklergm.witscompanion.signalexplorer.AudibleChange
import io.github.miklergm.witscompanion.signalexplorer.Correlator
import io.github.miklergm.witscompanion.signalexplorer.EventKind
import io.github.miklergm.witscompanion.signalexplorer.EventPayload
import io.github.miklergm.witscompanion.signalexplorer.Exporter
import io.github.miklergm.witscompanion.signalexplorer.GuidedSession
import io.github.miklergm.witscompanion.signalexplorer.MarkerType
import io.github.miklergm.witscompanion.signalexplorer.OtaPhase
import io.github.miklergm.witscompanion.signalexplorer.ReplayEngine
import io.github.miklergm.witscompanion.signalexplorer.SessionEvent
import io.github.miklergm.witscompanion.signalexplorer.SessionRecorder
import io.github.miklergm.witscompanion.signalexplorer.Tristate
import io.github.miklergm.witscompanion.signalexplorer.UserObservations

/**
 * Signal Explorer UI: live timeline, markers, guided wizard, sessions and replay.
 *
 * The screen contains no control that changes vehicle or head-unit state.
 */
class SignalExplorerSection(private val app: WitsCompanionApp) : MainActivity.Section {
    override val title = "Signals"

    private lateinit var statusView: TextView
    private lateinit var volumeView: TextView
    private lateinit var timelineView: TextView
    private lateinit var contextField: EditText
    private var activity: MainActivity? = null
    private var otaPhase = OtaPhase.UNSPECIFIED
    private var filter: String = ""
    private var replay: ReplayEngine? = null
    private val replayEvents = ArrayDeque<SessionEvent>()

    /** Marker buttons, greyed out while no session is recording. */
    private val markerButtons = mutableListOf<Button>()

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var refreshQueued = false

    /**
     * Events arrive on the probe threads (`wits-propprobe`, `wits-settingsprobe`), so the
     * refresh must be marshalled to the UI thread.
     *
     * `SessionRecorder.record()` notifies listeners synchronously and wraps each in
     * `runCatching`, so touching a View from here threw `CalledFromWrongThreadException`
     * that was then swallowed: recording worked, but the live timeline never updated once
     * and gave no hint why. `[RUNTIME]` 2026-07-31, reported as "nothing happened".
     *
     * Coalesced, because a burst of property changes would otherwise post one relayout each.
     */
    private val recorderListener = SessionRecorder.Listener {
        if (!refreshQueued) {
            refreshQueued = true
            ui.postDelayed({ refreshQueued = false; refreshStatus(); refreshTimeline() }, REFRESH_MS)
        }
    }

    override fun onCreateView(activity: MainActivity): View {
        this.activity = activity
        markerButtons.clear()
        val c = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 24))
        }

        c.addView(head(activity, "Signal Explorer"))
        c.addView(body(activity,
            "Observation only. This screen never sends a vendor broadcast, never writes a " +
                "property or setting, never changes volume, source or the steering scheme."))

        statusView = mono(activity, "—")
        c.addView(statusView)

        // ---------------------------------------------------------- volume panel
        c.addView(head(activity, "Volume domains"))
        volumeView = mono(activity, "—")
        c.addView(volumeView)
        c.addView(body(activity,
            "The OEM/NBT domain has no absolute value in this firmware, so it is shown as " +
                "unavailable rather than guessed. The relative estimate is opt-in and is " +
                "never presented as exact."))
        c.addView(btn(activity, "Toggle OEM relative estimate") {
            val on = app.signalExplorer.oemRelativeEstimate == null
            app.signalExplorer.enableRelativeEstimate(on)
            activity.toast(if (on) "Relative estimate ON (labelled an estimate)" else "OFF")
            refreshVolume()
        })
        c.addView(btn(activity, "Reset relative estimate") {
            app.signalExplorer.resetRelativeEstimate(); refreshVolume()
        })

        // -------------------------------------------------------------- session
        c.addView(head(activity, "Session"))
        contextField = EditText(activity).apply {
            hint = "Test context (e.g. 'scheme Type 1, engine off')"
            setSingleLine()
        }
        c.addView(contextField)

        c.addView(btn(activity, "OTA phase: ${otaPhase.name}") {
            otaPhase = when (otaPhase) {
                OtaPhase.UNSPECIFIED -> OtaPhase.BEFORE_OTA
                OtaPhase.BEFORE_OTA -> OtaPhase.AFTER_OTA
                OtaPhase.AFTER_OTA -> OtaPhase.UNSPECIFIED
            }
            (it as Button).text = "OTA phase: ${otaPhase.name}"
        })
        c.addView(btn(activity, "Start recording") {
            if (app.signalExplorer.isRecording) { activity.toast("Already recording"); return@btn }
            val raw = app.signalExplorer.steeringSchemeRaw()
            val rec = app.signalExplorer.startSession(otaPhase, contextField.text.toString(), raw)
            rec.addListener(recorderListener)
            activity.toast("Recording ${rec.metadata.sessionId}")
            refreshAll()
        })
        c.addView(btn(activity, "Stop recording") {
            app.signalExplorer.recorder?.removeListener(recorderListener)
            app.signalExplorer.stopSession()
            activity.toast("Stopped"); refreshAll()
        })

        // -------------------------------------------------------------- markers
        c.addView(head(activity, "Markers"))
        c.addView(body(activity,
            "Press the marker the instant you perform the physical action. Each marker " +
                "keeps 3 s of prior events and 8 s afterwards, plus audio snapshots."))
        val markerGrid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            MarkerType.STEERING_VOLUME_UP, MarkerType.STEERING_VOLUME_DOWN, MarkerType.STEERING_MUTE,
            MarkerType.NBT_KNOB_VOLUME_UP, MarkerType.NBT_KNOB_VOLUME_DOWN, MarkerType.NBT_KNOB_MUTE,
            MarkerType.SOURCE_OEM_TO_ANDROID, MarkerType.SOURCE_ANDROID_TO_OEM,
            MarkerType.IDRIVE_ROTATE_LEFT, MarkerType.IDRIVE_ROTATE_RIGHT, MarkerType.IDRIVE_PRESS,
            MarkerType.ANDROID_SPOTIFY_START, MarkerType.OEM_RADIO_START,
            MarkerType.OEM_BLUETOOTH_START, MarkerType.ZLINK_START,
            MarkerType.REVERSE_START, MarkerType.REVERSE_END, MarkerType.CUSTOM,
        ).chunked(3).forEach { row ->
            val rowView = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { type ->
                rowView.addView(Button(activity).apply {
                    text = type.name.replace('_', ' ').lowercase()
                    textSize = 11f
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        val m = app.signalExplorer.mark(type)
                        activity.toast(if (m == null) "Not recording" else "Marked ${type.name}")
                        refreshStatus(); refreshTimeline()
                    }
                    markerButtons += this
                })
            }
            markerGrid.addView(rowView)
        }
        c.addView(markerGrid)

        c.addView(btn(activity, "Answer observations for last marker") {
            showObservationDialog(activity)
        })

        // --------------------------------------------------------------- wizard
        c.addView(head(activity, "Guided session"))
        c.addView(body(activity,
            "Minimal matrix = 2 sources × 2 controls, the acceptance minimum. " +
                "You perform every physical action; the app only instructs and records."))
        c.addView(btn(activity, "Show minimal matrix steps") {
            val steps = GuidedSession.minimalMatrix().joinToString("\n\n") {
                "${it.index + 1}. [${it.source.label}] ${it.instruction} -> marker ${it.marker.name}"
            }
            showText(activity, "Minimal matrix", steps)
        })
        c.addView(btn(activity, "Show full matrix steps") {
            val steps = GuidedSession.fullMatrix().joinToString("\n") {
                "${it.index + 1}. [${it.source.label}] ${it.instruction.replace("\n", " ")}"
            }
            showText(activity, "Full matrix", steps)
        })

        // ------------------------------------------------------------- timeline
        c.addView(head(activity, "Live timeline"))
        val filterRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("all", "broadcast", "settings", "property", "key", "audio").forEach { f ->
            filterRow.addView(Button(activity).apply {
                text = f; textSize = 11f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { filter = if (f == "all") "" else f; refreshTimeline() }
            })
        }
        c.addView(filterRow)
        timelineView = mono(activity, "—")
        c.addView(timelineView)

        // ------------------------------------------------------------- sessions
        c.addView(head(activity, "Sessions"))
        c.addView(btn(activity, "List sessions") { showSessions(activity) })
        c.addView(btn(activity, "Export latest session") { exportLatest(activity) })
        c.addView(btn(activity, "Correlate latest markers") { showCorrelation(activity) })
        c.addView(btn(activity, "Replay latest session") { startReplay(activity) })
        c.addView(btn(activity, "Stop replay") {
            replay?.stop(); replay = null; activity.toast("Replay stopped")
        })

        refreshAll()
        return ScrollView(activity).apply { addView(c) }
    }

    override fun onResume() = refreshAll()

    override fun onCarState(state: CarState) {
        if (::statusView.isInitialized) refreshStatus()
    }

    // ------------------------------------------------------------------ refresh

    private fun refreshAll() { refreshStatus(); refreshVolume(); refreshTimeline() }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val ex = app.signalExplorer
        val rec = ex.recorder
        val raw = ex.steeringSchemeRaw()
        // Markers are meaningless outside a session; grey them out so the screen says so.
        markerButtons.forEach { it.isEnabled = ex.isRecording }
        statusView.text = buildString {
            appendLine("recording        : ${if (ex.isRecording) "YES" else "no — press Start recording"}")
            appendLine("session          : ${rec?.metadata?.sessionId ?: "—"}")
            appendLine("events / markers : ${rec?.eventCount ?: 0} / ${rec?.markerCount() ?: 0}")
            appendLine("catalog version  : ${ex.catalog.version} (${ex.catalog.subscribableActions().size} actions)")
            appendLine("property access  : ${ex.propertyStrategy()}")
            appendLine("missed samples   : ${ex.missedSamples()}")
            appendLine("steering scheme  : ${ex.steeringSchemeLabel(raw)} (raw ${raw ?: "unset"})")
            appendLine("catalog seen     : ${rec?.catalogDelta?.seenCount() ?: 0} actions")
        }
    }

    private fun refreshVolume() {
        if (!::volumeView.isInitialized) return
        volumeView.text = app.signalExplorer.volumeReadings().joinToString("\n") {
            "%-26s %s".format(it.domain.label, it.display())
        }
    }

    private fun refreshTimeline() {
        if (!::timelineView.isInitialized) return
        val events = app.signalExplorer.recorder?.recentEvents(60)
            ?: replayEvents.toList().asReversed()
        val shown = events.filter { e ->
            filter.isEmpty() || when (filter) {
                "broadcast" -> e.kind == EventKind.BROADCAST
                "settings" -> e.kind == EventKind.SETTINGS_CHANGE
                "property" -> e.kind == EventKind.PROPERTY_CHANGE
                "key" -> e.kind == EventKind.KEY_EVENT
                "audio" -> e.kind == EventKind.AUDIO_SNAPSHOT || e.kind == EventKind.AUDIO_CHANGE
                else -> true
            }
        }.take(40)

        timelineView.text = if (shown.isNotEmpty()) {
            shown.joinToString("\n") { e -> formatEvent(e) }
        } else if (!app.signalExplorer.isRecording) {
            "(not recording)\n\nPress \"Start recording\" above, then use a control in the " +
                "car — a steering button, the iDrive knob, the volume knob. Events appear here " +
                "as they arrive.\n\nMarker buttons only work while recording."
        } else if (events.isEmpty()) {
            "(recording — no events yet)\n\nNothing has changed since the session started. " +
                "Press a physical control, or switch source, to produce one."
        } else {
            "(recording — ${events.size} events, none match the \"$filter\" filter)\n\n" +
                "Press \"all\" to see everything."
        }
    }

    private fun formatEvent(e: SessionEvent): String {
        val p = (e.payload as? EventPayload.Raw)?.json ?: e.payload.toJson()
        val t = "%6d".format(e.seq)
        return when (e.kind) {
            EventKind.BROADCAST -> {
                val extras = p.optJSONArray("extras")
                val n = extras?.length() ?: 0
                val first = if (n > 0) {
                    val x = extras!!.getJSONObject(0)
                    " ${x.optString("name")}=${x.optString("value", x.optString("hex", "?"))}"
                } else ""
                "$t BCAST ${p.optString("action").substringAfterLast('.')}$first ($n extras)"
            }
            EventKind.SETTINGS_CHANGE ->
                "$t SET   ${p.optString("key")}: ${p.optString("old")} -> ${p.optString("new")}"
            EventKind.PROPERTY_CHANGE ->
                "$t PROP  ${p.optString("key")}: ${p.optString("old")} -> ${p.optString("new")}"
            EventKind.KEY_EVENT ->
                "$t KEY   ${p.optString("origin")} raw=${p.optString("rawCode", "-")} " +
                    "android=${p.optString("androidKeyCode", "-")} ${p.optString("action", "")}"
            EventKind.AUDIO_SNAPSHOT -> {
                val streams = p.optJSONArray("streams")
                val music = (0 until (streams?.length() ?: 0))
                    .map { streams!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "MUSIC" }
                "$t AUDIO ${p.optString("reason")} music=${music?.optInt("volume") ?: "?"}/${music?.optInt("max") ?: "?"}"
            }
            EventKind.MARKER -> "$t **MARK ${p.optString("markerType")}**"
            EventKind.NOTE -> "$t NOTE  ${p.optString("text")}"
            else -> "$t ${e.kind.name}"
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun showObservationDialog(activity: MainActivity) {
        if (app.signalExplorer.recorder?.markerCount()?.let { it > 0 } != true) {
            activity.toast("No marker yet"); return
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
        }
        var oem = Tristate.UNKNOWN
        var android = Tristate.UNKNOWN
        var audible = AudibleChange.UNCLEAR
        var both = Tristate.UNKNOWN
        var srcChanged = Tristate.UNKNOWN

        fun tri(label: String, onSet: (Tristate) -> Unit) {
            container.addView(body(activity, label))
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            Tristate.entries.forEach { v ->
                row.addView(Button(activity).apply {
                    text = v.name; textSize = 11f; isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { onSet(v); activity.toast("$label = ${v.name}") }
                })
            }
            container.addView(row)
        }
        tri("BMW/OEM volume graphic visible?") { oem = it }
        tri("Android volume graphic visible?") { android = it }
        tri("Both domains appear to change?") { both = it }
        tri("Source changed unexpectedly?") { srcChanged = it }

        container.addView(body(activity, "Audible level changed?"))
        val audRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        AudibleChange.entries.forEach { v ->
            audRow.addView(Button(activity).apply {
                text = v.name; textSize = 11f; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { audible = v; activity.toast("audible = ${v.name}") }
            })
        }
        container.addView(audRow)

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Observations for the last marker")
            .setView(ScrollView(activity).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                app.signalExplorer.attachObservations(
                    UserObservations(oem, android, audible, both, srcChanged)
                )
                activity.toast("Saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSessions(activity: MainActivity) {
        val sessions = SessionRecorder.listSessions(activity)
        if (sessions.isEmpty()) { activity.toast("No sessions"); return }
        val text = sessions.joinToString("\n") { d ->
            "%s  %d KB".format(d.name, Exporter.sessionSizeBytes(d) / 1024)
        }
        showText(activity, "Sessions (${sessions.size})", text)
    }

    private fun exportLatest(activity: MainActivity) {
        val dir = SessionRecorder.listSessions(activity).firstOrNull()
        if (dir == null) { activity.toast("No sessions"); return }
        val text = Exporter.bundleAsText(dir, app.signalExplorer.catalog)
        LogExportHelper.exportText(activity, text, "${dir.name}-signal-session.txt")
    }

    private fun showCorrelation(activity: MainActivity) {
        val dir = SessionRecorder.listSessions(activity).firstOrNull()
        if (dir == null) { activity.toast("No sessions"); return }
        val events = Exporter.readEvents(dir)
        val markers = app.signalExplorer.recorder?.allMarkers().orEmpty()
        if (markers.isEmpty()) { activity.toast("No markers in the active session"); return }
        val text = markers.joinToString("\n\n") { m ->
            val f = Correlator.analyse(m, events)
            buildString {
                appendLine("### ${f.marker.markerType.name}")
                appendLine("wits key broadcast : ${f.witsKeyBroadcast}")
                appendLine("raw key codes      : ${f.rawKeyCodes}")
                appendLine("android key events : ${f.androidKeyEvents}")
                appendLine("android stream Δ   : ${f.androidStreamDeltas.ifEmpty { listOf("none") }}")
                appendLine("volume broadcasts  : ${f.volumeBroadcasts.ifEmpty { listOf("none") }}")
                appendLine("MCU volume Δ       : ${f.mcuVolumeDelta ?: "none"}")
                appendLine("source changed     : ${f.sourceChanged}")
                appendLine("=> ${f.conclusion()}")
            }
        }
        showText(activity, "Correlation", text)
    }

    private fun startReplay(activity: MainActivity) {
        if (app.signalExplorer.isRecording) { activity.toast("Stop recording first"); return }
        val dir = SessionRecorder.listSessions(activity).firstOrNull()
        if (dir == null) { activity.toast("No sessions"); return }
        replayEvents.clear()
        val engine = ReplayEngine { e ->
            replayEvents.addLast(e)
            while (replayEvents.size > 200) replayEvents.removeFirst()
            refreshTimeline()
        }
        val n = engine.load(dir)
        if (n == 0) { activity.toast("Session has no events"); return }
        replay = engine
        engine.start()
        activity.toast("Replaying $n events (no vendor broadcast is emitted)")
    }

    private fun showText(activity: MainActivity, title: String, text: String) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply {
                addView(mono(activity, text).apply {
                    setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
                })
            })
            .setPositiveButton("Close", null)
            .show()
    }

    // -------------------------------------------------------------- view helpers

    private fun dp(a: MainActivity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    private fun head(a: MainActivity, t: String) = TextView(a).apply {
        text = t; textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(a, 16), 0, dp(a, 6))
    }

    private fun body(a: MainActivity, t: String) = TextView(a).apply {
        text = t; textSize = 13f; setPadding(0, dp(a, 2), 0, dp(a, 2))
    }

    private fun mono(a: MainActivity, t: String) = TextView(a).apply {
        text = t; textSize = 11f; typeface = Typeface.MONOSPACE
        setPadding(0, dp(a, 4), 0, dp(a, 4))
    }

    private fun btn(a: MainActivity, t: String, onClick: (View) -> Unit) = Button(a).apply {
        text = t; isAllCaps = false
        setOnClickListener { onClick(it) }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(a, 6) }
    }

    private companion object {
        /** Coalescing window for timeline refreshes driven by probe threads. */
        const val REFRESH_MS = 250L
    }
}
