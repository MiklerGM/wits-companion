package io.github.miklergm.witscompanion.signalexplorer

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

// ================================================================= Correlator

/**
 * Answers the routing question for one marker, per docs/audio-volume.md §5.3.
 *
 * It reports only what was observed. Where nothing was observed it says so — it never
 * infers an OEM volume from relative key events.
 */
object Correlator {

    data class Finding(
        val marker: MarkerRecord,
        val witsKeyBroadcast: Boolean,
        val rawKeyCodes: List<Int>,
        val androidKeyEvents: List<Int>,
        val androidStreamDeltas: List<String>,
        val volumeBroadcasts: List<String>,
        val mediaSessionEvents: Int,
        val mcuVolumeDelta: Pair<String?, String?>?,   // old -> new of wits_mcu:1
        val otherSettingsChanges: List<String>,
        val propertyChanges: List<String>,
        val sourceChanged: Boolean,
    ) {
        /** A short, honest interpretation. Never asserts an OEM value. */
        fun conclusion(): String = when {
            androidStreamDeltas.isNotEmpty() && mcuVolumeDelta != null ->
                "Both Android stream and MCU volume changed"
            androidStreamDeltas.isNotEmpty() ->
                "Android stream changed; no MCU volume change observed"
            mcuVolumeDelta != null ->
                "MCU volume changed; Android stream unchanged"
            witsKeyBroadcast ->
                "Wits key event seen, but neither Android stream nor MCU volume changed " +
                    "— likely routed to the OEM path or consumed elsewhere"
            else ->
                "Nothing observable in Android — likely an OEM-only path"
        }
    }

    fun analyse(marker: MarkerRecord, events: List<SessionEvent>): Finding {
        val window = events.filter {
            it.seq >= marker.preSeqFrom && (marker.postSeqTo < 0 || it.seq <= marker.postSeqTo)
        }

        val broadcasts = window.mapNotNull { it.payload as? EventPayload.Broadcast }
        val keys = window.mapNotNull { it.payload as? EventPayload.KeyEventPayload }
        val changes = window.mapNotNull { it.payload as? EventPayload.KeyValueChange }
        val snapshots = window.mapNotNull { it.payload as? EventPayload.AudioSnapshotPayload }
            .map { it.snapshot }

        val pre = snapshots.firstOrNull { it.reason == "MARKER_PRE" }
        val post = snapshots.lastOrNull { it.reason == "MARKER_POST" }
        val streamDeltas = if (pre != null && post != null) post.deltaFrom(pre) else emptyList()

        val mcuChange = changes.firstOrNull {
            it.kind == EventKind.SETTINGS_CHANGE && it.key == SignalExplorer.MCU_VOLUME_KEY
        }?.let { normaliseMcu(it.old) to normaliseMcu(it.new) }

        return Finding(
            marker = marker,
            witsKeyBroadcast = broadcasts.any { it.action == "com.can.ACTION_KEY_CODE" },
            rawKeyCodes = keys.filter { it.origin == "WITS_BROADCAST" }.mapNotNull { it.rawCode },
            androidKeyEvents = keys.filter { it.origin == "ANDROID_FOCUSED" }.mapNotNull { it.androidKeyCode },
            androidStreamDeltas = streamDeltas,
            volumeBroadcasts = broadcasts.map { it.action }
                .filter { it.contains("VOLUME", true) || it.contains("MUTE", true) }
                .distinct(),
            mediaSessionEvents = window.count { it.kind == EventKind.MEDIA_STATE },
            mcuVolumeDelta = mcuChange,
            otherSettingsChanges = changes
                .filter { it.kind == EventKind.SETTINGS_CHANGE && it.key != SignalExplorer.MCU_VOLUME_KEY }
                .map { "${it.namespace}/${it.key}: ${it.old} -> ${it.new}" },
            propertyChanges = changes
                .filter { it.kind == EventKind.PROPERTY_CHANGE }
                .map { "${it.key}: ${it.old} -> ${it.new}" },
            sourceChanged = window.mapNotNull { it.sourceState.source }.distinct().size > 1,
        )
    }

    /** wits_mcu:1 packs the volume in the low byte (McuManager.java:1679-1686). */
    fun normaliseMcu(raw: String?): String? =
        raw?.trim()?.toIntOrNull()?.and(0xFF)?.toString() ?: raw
}

// ================================================================ ReplayEngine

/**
 * Replays a recorded session off-vehicle.
 *
 * **It never emits a vendor broadcast.** It only re-delivers recorded events to
 * in-process listeners so the timeline and dashboards can be developed and demonstrated
 * without the car.
 */
class ReplayEngine(private val onEvent: (SessionEvent) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var events: List<SessionEvent> = emptyList()
    private var index = 0

    @Volatile var isPlaying: Boolean = false; private set
    var speed: Double = 1.0

    fun load(sessionDir: File): Int {
        val f = File(sessionDir, "events.jsonl")
        if (!f.exists()) return 0
        events = f.readLines().mapNotNull { line ->
            line.takeIf { it.isNotBlank() }
                ?.let { runCatching { SessionEvent.fromJson(JSONObject(it)) }.getOrNull() }
        }.sortedBy { it.seq }
        index = 0
        return events.size
    }

    fun start() {
        if (events.isEmpty() || isPlaying) return
        isPlaying = true
        index = 0
        step()
    }

    fun stop() {
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Deterministic: strictly `seq` order, inter-event gaps from elapsedRealtimeNanos. */
    private fun step() {
        if (!isPlaying || index >= events.size) { isPlaying = false; return }
        val event = events[index]
        onEvent(event)
        index++
        if (index >= events.size) { isPlaying = false; return }
        val gapNanos = events[index].elapsedRealtimeNanos - event.elapsedRealtimeNanos
        val delayMs = (gapNanos / 1_000_000.0 / speed).toLong().coerceIn(0L, 5_000L)
        handler.postDelayed(::step, delayMs)
    }

    fun eventCount(): Int = events.size
}

// =================================================================== Exporter

/**
 * Renders a session to CSV / Markdown alongside the JSONL already on disk, then hands
 * the bundle to the caller for SAF export. Nothing is uploaded — the app has no
 * INTERNET permission.
 */
object Exporter {

    fun buildDerivedFiles(sessionDir: File, catalog: EventCatalog): List<File> {
        val events = readEvents(sessionDir)
        val markers = readMarkers(sessionDir)
        val csv = File(sessionDir, "events.csv").also { writeCsv(it, events) }
        val md = File(sessionDir, "summary.md").also { writeSummary(it, sessionDir, events, markers) }
        return listOfNotNull(
            File(sessionDir, "metadata.json").takeIf { it.exists() },
            File(sessionDir, "events.jsonl").takeIf { it.exists() },
            File(sessionDir, "markers.jsonl").takeIf { it.exists() },
            File(sessionDir, "snapshots.jsonl").takeIf { it.exists() },
            File(sessionDir, "catalog-delta.json").takeIf { it.exists() },
            csv, md,
        )
    }

    fun readEvents(sessionDir: File): List<SessionEvent> {
        val f = File(sessionDir, "events.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            l.takeIf { it.isNotBlank() }
                ?.let { runCatching { SessionEvent.fromJson(JSONObject(it)) }.getOrNull() }
        }
    }

    fun readMarkers(sessionDir: File): List<JSONObject> {
        val f = File(sessionDir, "markers.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            l.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
    }

    private fun csvEscape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s

    fun writeCsv(target: File, events: List<SessionEvent>) {
        val sb = StringBuilder(
            "seq,wallClockMs,elapsedRealtimeNanos,kind,action_or_key,name,javaType,value,source,reverse,topPackage,marker\n"
        )
        events.forEach { e ->
            val p = (e.payload as? EventPayload.Raw)?.json ?: e.payload.toJson()
            val src = e.sourceState
            val kind = e.kind.name
            val action = p.optString("action", "").ifEmpty { p.optString("key", "") }
                .ifEmpty { p.optString("markerType", "") }
            val marker = p.optString("markerType", "")

            val extras = p.optJSONArray("extras")
            if (extras != null && extras.length() > 0) {
                for (i in 0 until extras.length()) {
                    val x = extras.getJSONObject(i)
                    sb.append(
                        listOf(
                            e.seq.toString(), e.wallClockMs.toString(), e.elapsedRealtimeNanos.toString(),
                            kind, action, x.optString("name"), x.optString("javaType"),
                            x.optString("value", x.optString("hex", "")),
                            src.source?.toString() ?: "", src.reverse?.toString() ?: "",
                            src.topPackage ?: "", marker,
                        ).joinToString(",") { csvEscape(it) }
                    ).append('\n')
                }
            } else {
                val value = p.optString("new", "").ifEmpty { p.optString("text", "") }
                sb.append(
                    listOf(
                        e.seq.toString(), e.wallClockMs.toString(), e.elapsedRealtimeNanos.toString(),
                        kind, action, "", "", value,
                        src.source?.toString() ?: "", src.reverse?.toString() ?: "",
                        src.topPackage ?: "", marker,
                    ).joinToString(",") { csvEscape(it) }
                ).append('\n')
            }
        }
        target.writeText(sb.toString())
    }

    fun writeSummary(
        target: File,
        sessionDir: File,
        events: List<SessionEvent>,
        markers: List<JSONObject>,
    ) {
        val meta = runCatching {
            JSONObject(File(sessionDir, "metadata.json").readText())
        }.getOrNull()

        val sb = StringBuilder()
        sb.appendLine("# Signal Explorer session ${sessionDir.name}")
        sb.appendLine()
        sb.appendLine("> Observations only. Nothing here is a proven routing conclusion until")
        sb.appendLine("> a human promotes it in docs/ with a [RUNTIME] label.")
        sb.appendLine()
        meta?.let {
            sb.appendLine("## Metadata")
            sb.appendLine()
            sb.appendLine("| Field | Value |")
            sb.appendLine("|---|---|")
            sb.appendLine("| OTA phase | ${it.optString("otaPhase")} |")
            sb.appendLine("| App version | ${it.optString("appVersion")} |")
            sb.appendLine("| Catalog version | ${it.optInt("catalogVersion")} |")
            sb.appendLine("| Property strategy | ${it.optString("propertyStrategy")} |")
            val scheme = it.optJSONObject("steeringScheme")
            sb.appendLine("| Steering scheme | ${scheme?.optString("label")} (raw ${scheme?.optString("rawValue")}) |")
            val dev = it.optJSONObject("device")
            sb.appendLine("| Firmware | ${dev?.optString("buildDisplayId")} |")
            sb.appendLine("| MCU | ${dev?.optString("mcuVersion")} |")
            sb.appendLine("| Product id | ${dev?.optString("witsProductId")} |")
            sb.appendLine("| User context | ${it.optString("userContext")} |")
            sb.appendLine()
        }

        sb.appendLine("## Totals")
        sb.appendLine()
        sb.appendLine("- events: ${events.size}")
        sb.appendLine("- markers: ${markers.size}")
        events.groupingBy { it.kind.name }.eachCount().toList().sortedByDescending { it.second }
            .forEach { (k, n) -> sb.appendLine("- $k: $n") }
        sb.appendLine()

        val actions = events.mapNotNull {
            ((it.payload as? EventPayload.Raw)?.json ?: it.payload.toJson())
                .optString("action", "").ifEmpty { null }
        }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
        if (actions.isNotEmpty()) {
            sb.appendLine("## Actions observed")
            sb.appendLine()
            sb.appendLine("| Action | Count |")
            sb.appendLine("|---|---|")
            actions.forEach { (a, n) -> sb.appendLine("| `$a` | $n |") }
            sb.appendLine()
        }

        if (markers.isNotEmpty()) {
            sb.appendLine("## Markers")
            sb.appendLine()
            markers.forEach { m ->
                sb.appendLine("### ${m.optString("markerType")}")
                sb.appendLine()
                sb.appendLine("- note: ${m.optString("note").ifEmpty { "—" }}")
                sb.appendLine("- window: seq ${m.optLong("preSeqFrom")}..${m.optLong("postSeqTo")}")
                sb.appendLine("- steering scheme raw: ${m.optString("steeringSchemeRaw", "?")}")
                val obs = m.optJSONObject("userObservations")
                if (obs != null) {
                    sb.appendLine("- OEM OSD: ${obs.optString("oemOsdVisible")}")
                    sb.appendLine("- Android OSD: ${obs.optString("androidOsdVisible")}")
                    sb.appendLine("- audible: ${obs.optString("audibleChange")}")
                    sb.appendLine("- both domains: ${obs.optString("bothDomainsChanged")}")
                }
                sb.appendLine()
            }
        }

        sb.appendLine("## Next step")
        sb.appendLine()
        sb.appendLine("Feed each marker through the decision tree in `docs/audio-volume.md` §5.3,")
        sb.appendLine("then fill `research/volume-routing-matrix.csv`.")
        target.writeText(sb.toString())
    }

    /** Concatenates the session into one text blob for a single-file SAF export. */
    fun bundleAsText(sessionDir: File, catalog: EventCatalog): String {
        val files = buildDerivedFiles(sessionDir, catalog)
        return buildString {
            files.forEach { f ->
                appendLine("===== ${f.name} =====")
                appendLine(runCatching { f.readText() }.getOrDefault("<unreadable>"))
                appendLine()
            }
        }
    }

    fun deleteSession(sessionDir: File): Boolean =
        runCatching { sessionDir.deleteRecursively() }.getOrDefault(false)

    fun sessionSizeBytes(sessionDir: File): Long =
        sessionDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    @Suppress("unused")
    fun exportHint(context: Context): String =
        "Exports go through the system file picker; nothing leaves the device by itself."
}
