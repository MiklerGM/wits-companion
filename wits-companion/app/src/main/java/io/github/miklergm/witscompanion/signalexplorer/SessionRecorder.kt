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
import java.util.concurrent.TimeUnit
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

    /**
     * True once every queued write has hit the disk and the executor is shut down. Only then
     * does the session directory hold the complete tail, closing markers and catalog delta —
     * exporting before this reads a truncated session.
     */
    @Volatile
    var finalized: Boolean = false
        private set

    private val bytesWritten = AtomicLong(0)

    @Volatile
    private var sizeCapLogged = false

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
     *
     * This walks the JSON tree with [LogRedactor.redactJson] rather than regexing the
     * serialized string: string-level redaction only matches MAC/VIN/long-digit shapes, so an
     * SSID, a paired phone name or a track title arriving in a vendor broadcast extra went to
     * disk verbatim — including through the `{"name":…,"value":…}` shape that hides the real
     * key from a plain key walk. docs/security.md promises key-aware redaction here; now it is.
     *
     * Writes stop at [MAX_SESSION_BYTES]. A session is a debugging aid on a head unit with a
     * small data partition, and broadcast extras can carry whole byte arrays as hex.
     */
    private fun appendRedacted(file: File, json: JSONObject) {
        if (bytesWritten.get() >= MAX_SESSION_BYTES) {
            if (!sizeCapLogged) {
                sizeCapLogged = true
                Log.w(TAG, "session size cap reached (${MAX_SESSION_BYTES} bytes); dropping further writes")
                runCatching {
                    File(sessionDir, "TRUNCATED").writeText(
                        "Session exceeded ${MAX_SESSION_BYTES} bytes and was truncated.\n"
                    )
                }
            }
            return
        }
        val text = LogRedactor.redactJson(json).toString() + "\n"
        file.appendText(text)
        bytesWritten.addAndGet(text.toByteArray().size.toLong())
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

    /**
     * Ends the session and finalizes it on the writer thread.
     *
     * Finalization is completion-based, not fire-and-forget. The old version queued the closing
     * marker and catalog writes and returned immediately, leaving the executor running: an
     * export starting right after could read a session missing its tail, its closing markers or
     * its catalog delta, and every finished session leaked its writer thread.
     *
     * Order: stop accepting events, drain what is pending, write the catalog delta, mark
     * [finalized], shut the executor down. [onFinalized] runs on the writer thread once the
     * queue is empty — post back to your own thread if you need to touch UI.
     */
    fun stop(onFinalized: (() -> Unit)? = null) {
        if (!active) {
            // Already stopping or stopped: do not queue a second finalization, but still let
            // the caller learn when the first one is done.
            if (finalized) onFinalized?.invoke() else io.execute { onFinalized?.invoke() }
            return
        }
        active = false   // producers stop here; record() and recordSnapshot() are now no-ops

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
            }.onFailure { Log.w(TAG, "catalog delta write failed: ${it.message}") }
            finalized = true
            runCatching { onFinalized?.invoke() }
                .onFailure { Log.w(TAG, "finalize callback failed: ${it.message}") }
        }
        // Accept no further work; everything already queued still runs.
        io.shutdown()
    }

    /**
     * Blocks until finalization completes, for callers that genuinely cannot proceed without
     * the full session on disk (tests, and the export path). Returns false on timeout.
     */
    fun awaitFinalized(timeoutMs: Long): Boolean =
        runCatching { io.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

    companion object {
        private const val TAG = "WitsSessionRecorder"
        const val DEFAULT_RING_CAPACITY = 2000

        /**
         * Ceiling on one session's JSONL. Broadcast extras can carry whole byte arrays as hex,
         * and this writes to the head unit's data partition.
         */
        const val MAX_SESSION_BYTES = 32L * 1024 * 1024
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
