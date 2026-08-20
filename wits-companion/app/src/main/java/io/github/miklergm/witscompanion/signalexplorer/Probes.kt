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
        /** Recursively flattens a Bundle; arrays get indexed names. */
        fun flatten(bundle: Bundle, prefix: String = ""): List<CapturedExtra> {
            val out = mutableListOf<CapturedExtra>()
            for (key in bundle.keySet()) {
                val name = if (prefix.isEmpty()) key else "$prefix.$key"
                val value = runCatching {
                    @Suppress("DEPRECATION")
                    bundle.get(key)
                }.getOrNull()
                out += describe(name, value)
            }
            return out
        }

        fun describe(name: String, value: Any?): List<CapturedExtra> = when (value) {
            null -> listOf(CapturedExtra(name, "null", null))

            is ByteArray -> listOf(
                CapturedExtra(
                    name = name,
                    javaType = "byte[]",
                    value = null,
                    length = value.size,
                    hex = value.toHex(),
                    base64 = Base64.encodeToString(value, Base64.NO_WRAP),
                )
            )

            is Bundle -> flatten(value, name)

            is Array<*> -> value.flatMapIndexed { i, v -> describe("$name[$i]", v) }
            is IntArray -> value.mapIndexed { i, v ->
                CapturedExtra("$name[$i]", "int", v.toString())
            }
            is LongArray -> value.mapIndexed { i, v ->
                CapturedExtra("$name[$i]", "long", v.toString())
            }
            is FloatArray -> value.mapIndexed { i, v ->
                CapturedExtra("$name[$i]", "float", v.toString())
            }
            is BooleanArray -> value.mapIndexed { i, v ->
                CapturedExtra("$name[$i]", "boolean", v.toString())
            }
            is ArrayList<*> -> value.flatMapIndexed { i, v -> describe("$name[$i]", v) }

            else -> listOf(
                CapturedExtra(name, value.javaClass.name, value.toString())
            )
        }

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
