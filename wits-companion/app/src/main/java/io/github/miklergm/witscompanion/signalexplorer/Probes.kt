package io.github.miklergm.witscompanion.signalexplorer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import io.github.miklergm.witscompanion.carstate.PropertyReader

/**
 * Observation probes for the Signal Explorer.
 *
 * Every probe in this file is **read-only**. None of them sends a broadcast, writes a
 * property, writes a Settings key, injects input, or changes audio state.
 * See docs/security-signal-explorer.md.
 */

// ============================================================ BroadcastProbe

/**
 * Subscribes to every catalogued action and dumps each intent with real Java types.
 *
 * Registered with [ContextCompat.RECEIVER_EXPORTED] because the senders are other
 * processes (CenterService, SystemUI, the platform). That is required for cross-process
 * vendor broadcasts to arrive at all; the receiver itself is created at runtime and is
 * never declared in the manifest, so it exists only while a session is recording.
 */
class BroadcastProbe(
    private val catalog: EventCatalog,
    private val sourceStateProvider: () -> SourceState,
    private val onEvent: (EventKind, EventPayload, SourceState) -> Unit,
) : BroadcastReceiver() {

    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            catalog.subscribableActions().forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun stop(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(this) }
        registered = false
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val extras = intent.extras?.let { flatten(it) } ?: emptyList()
        val (unexpected, mismatches) = catalog.checkExtras(action, extras)

        onEvent(
            EventKind.BROADCAST,
            EventPayload.Broadcast(
                action = action,
                catalogStatus = catalog.statusOf(action),
                extras = extras,
                unexpectedExtras = unexpected,
                typeMismatches = mismatches,
                senderPackage = intent.`package`,
            ),
            sourceStateProvider(),
        )

        // A Wits key broadcast is additionally surfaced as a KEY_EVENT for the correlator.
        if (action == "com.can.ACTION_KEY_CODE") {
            val code = extras.firstOrNull { it.name == "key_code" }?.value?.toIntOrNull()
            val status = extras.firstOrNull { it.name == "key_status" }?.value
            onEvent(
                EventKind.KEY_EVENT,
                EventPayload.KeyEventPayload("WITS_BROADCAST", code, null, status),
                sourceStateProvider(),
            )
        }
    }

    companion object {

        /**
         * Flattens a Bundle into named readings, **within a budget**.
         *
         * The receiver is registered `RECEIVER_EXPORTED`, because the whole point is to hear
         * what the vendor's processes send. That also means any installed app can send the
         * same actions with extras of its choosing, and this ran on whatever arrived with no
         * limit of any kind: nested Bundles recursed to whatever depth the sender built, a
         * primitive array became one [CapturedExtra] per element, and a byte array became a
         * hex string twice its size plus a base64 string a third larger again. A single
         * broadcast near the ~1 MB Binder ceiling could expand into many megabytes of text —
         * inside `onReceive`, on the main thread — and [SessionRecorder] retains 2000 events.
         *
         * Nothing hostile is needed to reach it either; a chatty app with a large payload does
         * just as well. The existing caps are on the *session file*, which is downstream of the
         * memory this allocates.
         *
         * Three axes are bounded: [MAX_DEPTH] of nesting, [MAX_EXTRAS] readings, and
         * [MAX_CAPTURE_CHARS] of text in total, with [MAX_BINARY_BYTES] of any one blob
         * rendered. All of them sit far above any real vendor payload — the CAN broadcasts this
         * exists to capture carry a handful of small extras and 8-byte frames — and far below
         * anything that threatens the heap. A full ring of maximal captures is about 32 MB,
         * which is deliberately the same ceiling the session file already has.
         *
         * A truncated capture **says so**. It carries a [TRUNCATION_MARKER] reading naming the
         * axis that was hit, blobs keep their true `length`, and a shortened value ends in an
         * ellipsis. A research tool that silently dropped evidence would be worse than one that
         * captures less.
         */
        fun flatten(bundle: Bundle, prefix: String = ""): List<CapturedExtra> =
            withBudget { budget -> flatten(bundle, prefix, budget, depth = 0) }

        /** One value, described within a fresh budget. See [flatten]. */
        fun describe(name: String, value: Any?): List<CapturedExtra> =
            withBudget { budget -> describe(name, value, budget, depth = 0) }

        private inline fun withBudget(body: (Budget) -> List<CapturedExtra>): List<CapturedExtra> {
            val budget = Budget()
            val out = body(budget)
            val cut = budget.truncatedBy ?: return out
            return out + CapturedExtra(TRUNCATION_MARKER, "marker", "truncated:$cut")
        }

        private fun flatten(
            bundle: Bundle,
            prefix: String,
            budget: Budget,
            depth: Int,
        ): List<CapturedExtra> {
            val out = mutableListOf<CapturedExtra>()
            // keySet() is ordered, so a truncated capture is a prefix of the full one rather
            // than an arbitrary subset.
            for (key in bundle.keySet()) {
                if (budget.exhausted) break
                val name = if (prefix.isEmpty()) key else "$prefix.$key"
                val value = runCatching {
                    @Suppress("DEPRECATION")
                    bundle.get(key)
                }.getOrNull()
                out += describe(name, value, budget, depth)
            }
            return out
        }

        private fun describe(
            name: String,
            value: Any?,
            budget: Budget,
            depth: Int,
        ): List<CapturedExtra> {
            // The gate belongs here, not in one branch of the `when` below. It used to sit only
            // on the Bundle case, so `Object[]` and `ArrayList` recursed to whatever depth the
            // sender built — the two container types that can hold each other, and the two most
            // easily nested from an ordinary Intent extra.
            if (depth >= MAX_DEPTH && value.isRecursiveContainer) {
                budget.note("depth")
                return budget.emit(
                    CapturedExtra(name, value!!.javaClass.name, "truncated: deeper than $MAX_DEPTH")
                )
            }
            return describeValue(name, value, budget, depth)
        }

        /** Containers that can hold another container, and so make depth a real limit. */
        private val Any?.isRecursiveContainer: Boolean
            get() = this is Bundle || this is Array<*> || this is ArrayList<*>

        private fun describeValue(
            name: String,
            value: Any?,
            budget: Budget,
            depth: Int,
        ): List<CapturedExtra> = when (value) {
            null -> budget.emit(CapturedExtra(name, "null", null))

            is ByteArray -> {
                val shown = if (value.size > MAX_BINARY_BYTES) value.copyOf(MAX_BINARY_BYTES) else value
                budget.emit(
                    CapturedExtra(
                        name = name,
                        javaType = "byte[]",
                        // Null when whole, a note when not — the blob's true size is in `length`
                        // either way, so a capture never understates what arrived.
                        value = if (shown.size == value.size) null
                        else "truncated: first ${shown.size} of ${value.size} bytes",
                        length = value.size,
                        hex = shown.toHex(),
                        base64 = Base64.encodeToString(shown, Base64.NO_WRAP),
                    )
                )
            }

            is Bundle -> flatten(value, name, budget, depth + 1)

            is Array<*> -> budget.each(value.size) { i ->
                describe("$name[$i]", value[i], budget, depth + 1)
            }
            is IntArray -> budget.each(value.size) { i ->
                budget.emit(CapturedExtra("$name[$i]", "int", value[i].toString()))
            }
            is LongArray -> budget.each(value.size) { i ->
                budget.emit(CapturedExtra("$name[$i]", "long", value[i].toString()))
            }
            is FloatArray -> budget.each(value.size) { i ->
                budget.emit(CapturedExtra("$name[$i]", "float", value[i].toString()))
            }
            is BooleanArray -> budget.each(value.size) { i ->
                budget.emit(CapturedExtra("$name[$i]", "boolean", value[i].toString()))
            }
            is ArrayList<*> -> budget.each(value.size) { i ->
                describe("$name[$i]", value[i], budget, depth + 1)
            }

            else -> budget.emit(
                CapturedExtra(name, value.javaClass.name, budget.shorten(value.toString()))
            )
        }

        /**
         * What is left to spend on one capture.
         *
         * Counting readings alone would not bound anything — one reading can be a megabyte of
         * text — and counting characters alone would allow millions of empty ones. Both, and
         * whichever runs out first stops the walk.
         */
        private class Budget {
            private var readings = MAX_EXTRAS
            private var chars = MAX_CAPTURE_CHARS

            /** The axis that first forced something to be dropped, or null if nothing was. */
            var truncatedBy: String? = null
                private set

            val exhausted: Boolean get() = readings <= 0 || chars <= 0

            fun note(axis: String) {
                if (truncatedBy == null) truncatedBy = axis
            }

            /** Charges [extra] against the budget, or drops it and records why. */
            fun emit(extra: CapturedExtra): List<CapturedExtra> {
                if (readings <= 0) { note("count"); return emptyList() }
                val cost = extra.name.length + (extra.value?.length ?: 0) +
                    (extra.hex?.length ?: 0) + (extra.base64?.length ?: 0)
                if (cost > chars) { note("size"); return emptyList() }
                readings--
                chars -= cost
                return listOf(extra)
            }

            /** Walks [count] elements, stopping the moment the budget runs out. */
            inline fun each(
                count: Int,
                element: (Int) -> List<CapturedExtra>,
            ): List<CapturedExtra> {
                val out = mutableListOf<CapturedExtra>()
                for (i in 0 until count) {
                    if (exhausted) { note("count"); break }
                    out += element(i)
                }
                return out
            }

            /** [text] cut to [MAX_VALUE_CHARS], marked when it was. */
            fun shorten(text: String): String =
                if (text.length <= MAX_VALUE_CHARS) text
                else {
                    note("size")
                    text.take(MAX_VALUE_CHARS) + "\u2026"
                }
        }

        /** Name of the reading appended to a capture that had to drop something. */
        const val TRUNCATION_MARKER = "_captureTruncated"

        /** How deep nested Bundles, object arrays and lists are followed. */
        const val MAX_DEPTH = 8

        /** How many readings one broadcast may produce. */
        const val MAX_EXTRAS = 512

        /** Total text one capture may hold, names and rendered blobs included. */
        const val MAX_CAPTURE_CHARS = 16_384

        /** Longest single rendered value. */
        const val MAX_VALUE_CHARS = 4_096

        /** How much of a blob is rendered to hex and base64; `length` still reports it all. */
        const val MAX_BINARY_BYTES = 1_024

        fun ByteArray.toHex(): String =
            joinToString("") { "%02X".format(it) }
    }
}

// ================================================================ AudioProbe

/**
 * Reads Android audio state. Never changes it — and on this firmware could not, because
 * `AudioService` rejects volume changes from any package other than `com.wits.pms`
 * (docs/audio-volume.md §1).
 */
class AudioProbe(private val context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)

    fun snapshot(reason: String): AudioSnapshot {
        val streams = STREAMS.mapNotNull { (id, name) ->
            runCatching {
                StreamState(
                    stream = id,
                    name = name,
                    volume = am.getStreamVolume(id),
                    max = am.getStreamMaxVolume(id),
                    min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        am.getStreamMinVolume(id) else 0,
                    db = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        runCatching {
                            am.getStreamVolumeDb(
                                id, am.getStreamVolume(id),
                                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                            )
                        }
                            .getOrNull() else null,
                    muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        runCatching { am.isStreamMute(id) }.getOrDefault(false) else false,
                )
            }.getOrNull()
        }

        val outputs = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d ->
                "type=${d.type}${d.productName?.let { " name=$it" } ?: ""}"
            }
        }.getOrDefault(emptyList())

        val playback = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.activePlaybackConfigurations.map { c ->
                    "usage=${c.audioAttributes.usage} content=${c.audioAttributes.contentType}"
                }
            } else emptyList()
        }.getOrDefault(emptyList())

        return AudioSnapshot(
            reason = reason,
            streams = streams,
            ringerMode = runCatching { am.ringerMode }.getOrNull(),
            audioMode = runCatching { am.mode }.getOrNull(),
            micMuted = runCatching { am.isMicrophoneMute }.getOrNull(),
            musicActive = runCatching { am.isMusicActive }.getOrNull(),
            outputDevices = outputs,
            activePlayback = playback,
        )
    }

    /** Truthful multi-domain reading; see docs/audio-volume.md §7. */
    fun volumeReadings(mcuRaw: String?, relativeEstimate: Int?): List<VolumeReading> {
        val media = runCatching {
            VolumeReading(
                VolumeDomain.ANDROID_MEDIA,
                am.getStreamVolume(AudioManager.STREAM_MUSIC),
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                Availability.VALID,
            )
        }.getOrDefault(VolumeReading(VolumeDomain.ANDROID_MEDIA, null, null, Availability.UNKNOWN))

        // wits_mcu:1 packs the MCU volume in its low byte on M701 (McuManager.java:1679-1686).
        val mcuValue = mcuRaw?.trim()?.toIntOrNull()?.and(0xFF)
        val mcu = VolumeReading(
            VolumeDomain.WITS_MCU,
            mcuValue,
            MCU_MAX_VOLUME,
            if (mcuValue == null) Availability.UNKNOWN else Availability.VALID,
            rawValue = mcuRaw,
        )

        // No absolute OEM/NBT value has been found in this firmware ([NOTFOUND]).
        val oem = VolumeReading(VolumeDomain.OEM_NBT, null, null, Availability.UNKNOWN)

        val estimate = relativeEstimate?.let {
            VolumeReading(VolumeDomain.OEM_RELATIVE_ESTIMATE, it, null, Availability.OBSERVED)
        }

        return listOfNotNull(media, mcu, oem, estimate)
    }

    companion object {
        /** MAX_VOLUME = 40 for M701 (McuManager.java:1658). */
        const val MCU_MAX_VOLUME = 40

        val STREAMS = listOf(
            AudioManager.STREAM_VOICE_CALL to "VOICE_CALL",
            AudioManager.STREAM_SYSTEM to "SYSTEM",
            AudioManager.STREAM_RING to "RING",
            AudioManager.STREAM_MUSIC to "MUSIC",
            AudioManager.STREAM_ALARM to "ALARM",
            AudioManager.STREAM_NOTIFICATION to "NOTIFICATION",
            AudioManager.STREAM_DTMF to "DTMF",
            AudioManager.STREAM_ACCESSIBILITY to "ACCESSIBILITY",
        )
    }
}

// ============================================================= PropertyProbe

/**
 * Samples targeted properties on a background thread and emits changes.
 *
 * Reuses the existing [PropertyReader] (reflection first, bulk `getprop` fallback).
 * A full `getprop` is never run in a tight loop: the bulk refresh happens once per tick,
 * and the tick is deliberately coarse.
 */
class PropertyProbe(
    private val reader: PropertyReader,
    private val targeted: List<String>,
    private val allProperties: List<String>,
    private val intervalMs: Long = 500L,
    private val onChange: (key: String, old: String?, new: String?) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val last = HashMap<String, String?>()

    @Volatile var missedSamples: Long = 0; private set
    @Volatile var sampleCount: Long = 0; private set

    val strategy: PropertyReader.Strategy get() = reader.activeStrategy
    val diagnostics: String get() = reader.diagnostics

    fun start() {
        if (thread != null) return
        val t = HandlerThread("wits-propprobe").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(tick) }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null; handler = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val started = SystemClock.elapsedRealtime()
            runCatching {
                reader.refreshBulk()
                targeted.forEach { key ->
                    val now = reader.get(key)
                    val had = last.containsKey(key)
                    val before = last[key]
                    if (!had || before != now) {
                        last[key] = now
                        if (had) onChange(key, before, now)
                    }
                }
                sampleCount++
            }.onFailure { missedSamples++ }
            val elapsed = SystemClock.elapsedRealtime() - started
            handler?.postDelayed(this, (intervalMs - elapsed).coerceAtLeast(100L))
        }
    }

    /** Full read of every catalogued property, for session start/marker/end. */
    fun snapshot(): Map<String, String?> {
        reader.refreshBulk()
        return allProperties.associateWith { reader.get(it) }
    }
}

// ============================================================= SettingsProbe

/**
 * Snapshots and diffs `Settings.System` / `Global` / `Secure`.
 *
 * A ContentObserver on Android 13 does not reliably tell you *which* key changed, so the
 * probe combines observers (as a change hint) with filtered snapshots and diffs.
 * It never writes.
 */
class SettingsProbe(
    private val context: Context,
    private val watchedSystem: List<String>,
    private val watchedGlobal: List<String>,
    private val onChange: (namespace: String, key: String, old: String?, new: String?) -> Unit,
) {
    private val cr = context.contentResolver
    private var observer: ContentObserver? = null
    private var handlerThread: HandlerThread? = null
    private val lastSystem = HashMap<String, String?>()
    private val lastGlobal = HashMap<String, String?>()

    fun start() {
        if (observer != null) return
        val t = HandlerThread("wits-settingsprobe").also { it.start() }
        handlerThread = t
        // Prime the baseline so the first diff is meaningful.
        watchedSystem.forEach { lastSystem[it] = readSystem(it) }
        watchedGlobal.forEach { lastGlobal[it] = readGlobal(it) }

        observer = object : ContentObserver(Handler(t.looper)) {
            override fun onChange(selfChange: Boolean) = diffNow()
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) = diffNow()
        }.also {
            runCatching { cr.registerContentObserver(Settings.System.CONTENT_URI, true, it) }
            runCatching { cr.registerContentObserver(Settings.Global.CONTENT_URI, true, it) }
        }
    }

    fun stop() {
        observer?.let { runCatching { cr.unregisterContentObserver(it) } }
        observer = null
        handlerThread?.quitSafely(); handlerThread = null
    }

    fun diffNow() {
        watchedSystem.forEach { key ->
            val now = readSystem(key)
            if (lastSystem[key] != now) {
                onChange("system", key, lastSystem[key], now)
                lastSystem[key] = now
            }
        }
        watchedGlobal.forEach { key ->
            val now = readGlobal(key)
            if (lastGlobal[key] != now) {
                onChange("global", key, lastGlobal[key], now)
                lastGlobal[key] = now
            }
        }
    }

    fun snapshot(): Map<String, String?> = buildMap {
        watchedSystem.forEach { put("system/$it", readSystem(it)) }
        watchedGlobal.forEach { put("global/$it", readGlobal(it)) }
    }

    fun readSystem(key: String): String? =
        runCatching { Settings.System.getString(cr, key) }.getOrNull()

    fun readGlobal(key: String): String? =
        runCatching { Settings.Global.getString(cr, key) }.getOrNull()

    /**
     * A broad, filtered scan used for the "unknown key" hunt. Reads whole tables where
     * permitted and keeps only keys matching the priority regex.
     */
    fun filteredScan(): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for ((ns, uri) in listOf(
            "system" to Settings.System.CONTENT_URI,
            "global" to Settings.Global.CONTENT_URI,
            "secure" to Settings.Secure.CONTENT_URI,
        )) {
            runCatching {
                cr.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    val ni = c.getColumnIndex("name"); val vi = c.getColumnIndex("value")
                    while (c.moveToNext()) {
                        val name = c.getString(ni) ?: continue
                        if (PRIORITY.containsMatchIn(name)) out["$ns/$name"] = c.getString(vi)
                    }
                }
            }
        }
        return out
    }

    companion object {
        val PRIORITY = Regex(
            "wits|volume|audio|steer|wheel|key|can|mcu|source|ui|night|backlight|media|navi|bt|radio|aux",
            RegexOption.IGNORE_CASE,
        )
    }
}

// ================================================================ InputProbe

/**
 * Records Android KeyEvents that reach the companion **while it has window focus**.
 *
 * A normal app cannot observe global input. Anything beyond focused events must be
 * captured with `tools/capture-input-session.sh` (getevent / dumpsys input / logcat).
 * We deliberately do not add an Accessibility service.
 */
class InputProbe(
    private val onKey: (androidKeyCode: Int, action: String, repeat: Int) -> Unit,
) {
    fun onKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) "repeat" else "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "other"
        }
        onKey(event.keyCode, action, event.repeatCount)
        return false   // never consume; we only observe
    }

    companion object {
        val INTERESTING = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }
}
