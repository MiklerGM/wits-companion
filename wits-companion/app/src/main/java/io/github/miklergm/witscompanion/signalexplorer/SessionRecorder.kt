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
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
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

    /**
     * The actor thread. Everything below marked *actor-owned* is touched **only** here, which
     * is what replaced three overlapping concurrency strategies: a lock for the ring, a second
     * for the pending markers, an unsynchronized list for the markers themselves, and a
     * non-atomic `eventCount++` — all reached from several probe threads at once.
     */
    private val io = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { r -> Thread(r, "wits-session") },
    ) { _, _ -> dropped.incrementAndGet() }

    /**
     * Events discarded because the actor could not keep up.
     *
     * The queue is bounded, which the default single-thread executor's is not. Per-event caps
     * bound how large one capture can be; they say nothing about how many arrive. A flood of
     * broadcasts — from a chatty app or a wedged vendor service — would otherwise queue without
     * limit in front of a thread doing file I/O, and the ring and file caps downstream never
     * see them because the memory is held in the queue.
     *
     * Dropping is the right failure for a research recorder: it loses the tail of a burst
     * rather than the process. It is counted so a session can say it happened, because a
     * silently short capture would be worse than a short one that admits it.
     */
    private val dropped = AtomicLong(0)

    /** How many events were dropped for want of queue space. Read by the Debug screen. */
    val droppedEvents: Long get() = dropped.get()

    /**
     * Sequence numbers are handed out on the *calling* thread, not the actor.
     *
     * [record] must return the event it created — callers correlate on it — so the identity
     * has to exist before the work is queued. An atomic counter is the one piece of shared
     * state that genuinely needs to be, and it is lock-free.
     */
    private val seq = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<Listener>()

    // --- actor-owned: never touch these off the `io` thread ---------------------
    private val ring = ArrayDeque<SessionEvent>(ringCapacity)
    private val markers = mutableListOf<MarkerRecord>()
    private val pendingMarkers = mutableListOf<Pair<MarkerRecord, Long>>()   // marker, closeAtElapsedMs
    private val delta = CatalogDelta()

    // --- published snapshots: written by the actor, read from anywhere ----------

    /**
     * Immutable views the UI and the marker pre-window read instead of touching the live
     * collections. Republished after each event, so a reader can be at most one event behind
     * — which for a timeline and an approximate pre-window is not a meaningful difference,
     * and is a far better trade than a lock held across IO.
     */
    @Volatile
    private var ringSnapshot: List<SessionEvent> = emptyList()

    @Volatile
    private var markerSnapshot: List<MarkerRecord> = emptyList()

    /** The catalog delta, safe to read once [finalized]; mutated only on the actor. */
    val catalogDelta: CatalogDelta get() = delta

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

    /**
     * Records one event.
     *
     * The event is *built* here so the caller gets it back immediately; everything that
     * mutates shared state — ring, counters, catalog delta, listener notification, the file
     * write, closing due markers — happens on the actor, in the order the calls arrived.
     *
     * Listeners are therefore invoked on the actor thread, not on whichever probe thread
     * produced the event. That is deliberate: it gives them a single-threaded, ordered view,
     * and stops a probe thread running listener code that was written expecting otherwise.
     */
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
        submit { ingest(event) }
        return event
    }

    /** Actor-side handling of one event. */
    private fun ingest(event: SessionEvent) {
        ring.addLast(event)
        while (ring.size > ringCapacity) ring.removeFirst()
        ringSnapshot = ring.toList()
        // Safe unsynchronized: the actor is the only writer. The ring is capped, so this
        // counts everything ever recorded, not what is still buffered.
        eventCount += 1

        when (val payload = event.payload) {
            is EventPayload.Broadcast -> delta.record(
                payload.action, payload.extras, payload.unexpectedExtras, payload.typeMismatches
            )
            is EventPayload.AudioSnapshotPayload -> lastAudioSnapshot = payload.snapshot
            else -> Unit
        }

        runCatching { appendRedacted(eventsFile, event.toJson()) }
            .onFailure { Log.w(TAG, "event write failed: ${it.message}") }

        closeDuePendingMarkers(event.seq)
        listeners.forEach { runCatching { it.onEvent(event) } }
    }

    fun recordSnapshot(json: JSONObject) {
        if (!active) return
        submit { runCatching { appendRedacted(snapshotsFile, json) } }
    }

    /**
     * Queues actor work, tolerating the window after [stop] has shut the executor down.
     *
     * A probe that has not stopped yet can still call in; dropping that event is correct —
     * the session is closing — but a RejectedExecutionException propagating into a probe
     * thread is not.
     */
    private fun submit(work: () -> Unit) {
        runCatching { io.execute(work) }
            .onFailure { Log.w(TAG, "dropped work after shutdown: ${it.javaClass.simpleName}") }
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
        // Read the published snapshot rather than the live ring: the pre-window is an
        // approximate span of context, and being at most one event behind cannot change which
        // physical action it captures.
        val firstSeq = ringSnapshot.firstOrNull { it.elapsedRealtimeNanos >= cutoff }?.seq
            ?: seq.get()
        val marker = MarkerRecord(
            markerType = type,
            note = note,
            preWindowMs = preWindowMs,
            postWindowMs = postWindowMs,
            preSeqFrom = firstSeq,
            sourceAtMarker = sourceState.source,
            steeringSchemeRaw = steeringSchemeRaw,
        )
        val closeAt = SystemClock.elapsedRealtime() + postWindowMs
        // Registered before the marker event is queued, so the actor sees them in that order.
        submit {
            markers += marker
            markerSnapshot = markers.toList()
            pendingMarkers += marker to closeAt
        }
        record(EventKind.MARKER, EventPayload.MarkerPayload(marker), sourceState)
        return marker
    }

    /** Actor-side. Closes any marker whose post-window has elapsed. */
    private fun closeDuePendingMarkers(currentSeq: Long) {
        val now = SystemClock.elapsedRealtime()
        val due = pendingMarkers.filter { it.second <= now }
        if (due.isEmpty()) return
        pendingMarkers.removeAll(due.toSet())
        due.forEach { (marker, _) ->
            marker.postSeqTo = currentSeq
            runCatching { appendRedacted(markersFile, marker.toJson()) }
        }
    }

    /** Attaches the tester's answers to the most recent marker. */
    fun attachObservations(observations: UserObservations): MarkerRecord? {
        val marker = markerSnapshot.lastOrNull() ?: return null
        marker.userObservations = observations
        submit { runCatching { appendRedacted(markersFile, marker.toJson()) } }
        return marker
    }

    fun markerCount(): Int = markerSnapshot.size
    fun allMarkers(): List<MarkerRecord> = markerSnapshot

    // -------------------------------------------------------------------- access

    // Snapshot reads — no lock, and none of them can block a probe thread behind file IO.
    fun recentEvents(limit: Int = 200): List<SessionEvent> =
        ringSnapshot.takeLast(limit).asReversed()

    fun eventsInRange(fromSeq: Long, toSeq: Long): List<SessionEvent> =
        ringSnapshot.filter { it.seq in fromSeq..toSeq }

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

        val last = seq.get()
        io.execute {
            // Close any marker still waiting for its post-window. Actor-side like every other
            // mutation, and ordered after whatever events were already queued.
            pendingMarkers.forEach { (marker, _) ->
                marker.postSeqTo = last
                runCatching { appendRedacted(markersFile, marker.toJson()) }
            }
            pendingMarkers.clear()

            runCatching {
                File(sessionDir, "catalog-delta.json")
                    .writeText(delta.toJson(catalog).toString(2))
            }.onFailure { Log.w(TAG, "catalog delta write failed: ${it.message}") }
            finalized = true
            runCatching { onFinalized?.invoke() }
                .onFailure { Log.w(TAG, "finalize callback failed: ${it.message}") }
        }
        // Accept no further work; everything already queued still runs.
        io.shutdown()
    }

    /**
     * Blocks until every event queued *so far* has been ingested and written.
     *
     * Recording is asynchronous, so a caller that records and then immediately reads
     * [recentEvents] can legitimately see the event missing. This is the barrier for the cases
     * where that matters — tests, and anything about to read the files directly. It does not
     * end the session; use [stop] for that.
     *
     * @return false on timeout, or once the actor has been shut down by [stop]
     */
    fun flush(timeoutMs: Long = 5_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val queued = runCatching { io.execute { done.countDown() } }.isSuccess
        if (!queued) return false
        return runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
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
         * How many events may be waiting for the actor thread.
         *
         * Twice the ring, so a burst that the ring would keep is never dropped by the queue,
         * and bounded so a flood cannot outrun the recorder into the heap.
         */
        const val QUEUE_CAPACITY = 4000

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
