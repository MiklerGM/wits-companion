package io.github.miklergm.witscompanion.signalexplorer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.miklergm.witscompanion.logging.LogRedactor
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one recording session: the always-on ring buffer, the JSONL writer, markers and
 * the catalog delta.
 *
 * Writes are serialized on a single background executor so ordering is deterministic
 * and the UI thread never blocks on IO.
 *
 * See docs/session-format.md.
 */
class SessionRecorder(
    context: Context,
    val metadata: SessionMetadata,
    private val catalog: EventCatalog,
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
) {

    fun interface Listener {
        fun onEvent(event: SessionEvent)
    }

    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wits-session") }
    private val seq = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Recent events, for pre-marker context and the live timeline. */
    private val ring = ArrayDeque<SessionEvent>(ringCapacity)
    private val ringLock = Any()

    private val markers = mutableListOf<MarkerRecord>()
    private val pendingMarkers = mutableListOf<Pair<MarkerRecord, Long>>()   // marker, closeAtElapsedMs
    val catalogDelta = CatalogDelta()

    @Volatile
    var lastAudioSnapshot: AudioSnapshot? = null
        private set

    val sessionDir: File = File(appContext.filesDir, "sessions/${metadata.sessionId}")
        .also { it.mkdirs() }

    private val eventsFile = File(sessionDir, "events.jsonl")
    private val markersFile = File(sessionDir, "markers.jsonl")
    private val snapshotsFile = File(sessionDir, "snapshots.jsonl")

    @Volatile
    var eventCount: Long = 0
        private set

    @Volatile
    var active: Boolean = true
        private set

    init {
        io.execute {
            runCatching {
                File(sessionDir, "metadata.json").writeText(metadata.toJson().toString(2))
            }.onFailure { Log.w(TAG, "metadata write failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ recording

    fun record(
        kind: EventKind,
        payload: EventPayload,
        sourceState: SourceState = SourceState(),
        wallClockMs: Long = System.currentTimeMillis(),
        elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    ): SessionEvent? {
        if (!active) return null
        val event = SessionEvent(
            seq = seq.getAndIncrement(),
            wallClockMs = wallClockMs,
            elapsedRealtimeNanos = elapsedRealtimeNanos,
            kind = kind,
            sourceState = sourceState,
            payload = payload,
        )
        eventCount++

        synchronized(ringLock) {
            ring.addLast(event)
            while (ring.size > ringCapacity) ring.removeFirst()
        }

        if (payload is EventPayload.Broadcast) {
            catalogDelta.record(
                payload.action, payload.extras, payload.unexpectedExtras, payload.typeMismatches
            )
        }
        if (payload is EventPayload.AudioSnapshotPayload) {
            lastAudioSnapshot = payload.snapshot
        }

        io.execute {
            runCatching { appendRedacted(eventsFile, event.toJson()) }
                .onFailure { Log.w(TAG, "event write failed: ${it.message}") }
        }
        listeners.forEach { runCatching { it.onEvent(event) } }
        closeDuePendingMarkers(event.seq)
        return event
    }

    fun recordSnapshot(json: JSONObject) {
        if (!active) return
        io.execute { runCatching { appendRedacted(snapshotsFile, json) } }
    }

    /**
     * Redaction happens at write time so nothing sensitive ever reaches storage.
     * Media metadata keys are dropped unless verbose debugging is on elsewhere.
     */
    private fun appendRedacted(file: File, json: JSONObject) {
        val text = LogRedactor.redactValue(json.toString())
        file.appendText(text + "\n")
    }

    // -------------------------------------------------------------------- markers

    /**
     * Freezes the pre-window, records the marker, and keeps the post-window open until
     * [MarkerRecord.postWindowMs] has elapsed.
     */
    fun mark(
        type: MarkerType,
        note: String = "",
        preWindowMs: Long = DEFAULT_PRE_WINDOW_MS,
        postWindowMs: Long = DEFAULT_POST_WINDOW_MS,
        sourceState: SourceState = SourceState(),
        steeringSchemeRaw: String? = metadata.steeringSchemeRaw,
    ): MarkerRecord {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val cutoff = nowNanos - preWindowMs * 1_000_000L
        val firstSeq = synchronized(ringLock) {
            ring.firstOrNull { it.elapsedRealtimeNanos >= cutoff }?.seq ?: seq.get()
        }
        val marker = MarkerRecord(
            markerType = type,
            note = note,
            preWindowMs = preWindowMs,
            postWindowMs = postWindowMs,
            preSeqFrom = firstSeq,
            sourceAtMarker = sourceState.source,
            steeringSchemeRaw = steeringSchemeRaw,
        )
        markers += marker
        synchronized(pendingMarkers) {
            pendingMarkers += marker to (SystemClock.elapsedRealtime() + postWindowMs)
        }
        record(EventKind.MARKER, EventPayload.MarkerPayload(marker), sourceState)
        return marker
    }

    private fun closeDuePendingMarkers(currentSeq: Long) {
        val now = SystemClock.elapsedRealtime()
        val closed = synchronized(pendingMarkers) {
            val due = pendingMarkers.filter { it.second <= now }
            pendingMarkers.removeAll(due.toSet())
            due.map { it.first }
        }
        closed.forEach { m ->
            m.postSeqTo = currentSeq
            io.execute { runCatching { appendRedacted(markersFile, m.toJson()) } }
        }
    }

    /** Attaches the tester's answers to the most recent marker. */
    fun attachObservations(observations: UserObservations): MarkerRecord? {
        val marker = markers.lastOrNull() ?: return null
        marker.userObservations = observations
        io.execute { runCatching { appendRedacted(markersFile, marker.toJson()) } }
        return marker
    }

    fun markerCount(): Int = markers.size
    fun allMarkers(): List<MarkerRecord> = markers.toList()

    // -------------------------------------------------------------------- access

    fun recentEvents(limit: Int = 200): List<SessionEvent> =
        synchronized(ringLock) { ring.toList().takeLast(limit).asReversed() }

    fun eventsInRange(fromSeq: Long, toSeq: Long): List<SessionEvent> =
        synchronized(ringLock) { ring.filter { it.seq in fromSeq..toSeq } }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    // ---------------------------------------------------------------------- stop

    fun stop() {
        if (!active) return
        active = false
        // Close any marker still waiting for its post-window.
        val remaining = synchronized(pendingMarkers) {
            val all = pendingMarkers.map { it.first }; pendingMarkers.clear(); all
        }
        val last = seq.get()
        remaining.forEach { m ->
            m.postSeqTo = last
            io.execute { runCatching { appendRedacted(markersFile, m.toJson()) } }
        }
        io.execute {
            runCatching {
                File(sessionDir, "catalog-delta.json")
                    .writeText(catalogDelta.toJson(catalog).toString(2))
            }
        }
    }

    companion object {
        private const val TAG = "WitsSessionRecorder"
        const val DEFAULT_RING_CAPACITY = 2000
        const val DEFAULT_PRE_WINDOW_MS = 3_000L
        const val DEFAULT_POST_WINDOW_MS = 8_000L

        fun newSessionId(): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                "-" + (100000..999999).random().toString(16)

        fun listSessions(context: Context): List<File> =
            File(context.filesDir, "sessions").listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?: emptyList()
    }
}
