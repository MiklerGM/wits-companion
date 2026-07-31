package io.github.miklergm.witscompanion.signalexplorer

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for the Signal Explorer.
 *
 * Everything here is an *observation*, never a conclusion. The model deliberately keeps
 * raw values alongside parsed ones so nothing is lost, and it never lets "not observed"
 * collapse into zero.
 *
 * See docs/session-format.md.
 */

// --------------------------------------------------------------------- vocabulary

enum class Confidence { CODE, CONF, RUNTIME, HYP, NOTFOUND }

enum class CatalogStatus { KNOWN, PARTIAL, EXPERIMENTAL, UNSUPPORTED, UNCATALOGUED }

enum class SourceKind { BROADCAST, PROPERTY, SETTINGS, AUDIO, INPUT, MEDIA, DERIVED, SIMULATION }

enum class ValueType {
    BOOL_INT, INT, INT_PACKED, INT_UNKNOWN_SCALE, LONG, FLOAT,
    FLOAT_UNKNOWN_SCALE, STRING, BYTES, UNKNOWN
}

/** Reuses the vocabulary of carstate.Availability but is independent of it. */
enum class Availability { UNKNOWN, OBSERVED, VALID, STALE, UNSUPPORTED, INVALID }

enum class OtaPhase { BEFORE_OTA, AFTER_OTA, UNSPECIFIED }

// ------------------------------------------------------------------- definitions

data class SignalDefinition(
    val id: String,
    val displayName: String,
    val sourceKind: SourceKind,
    val sourceName: String,
    val expectedType: ValueType,
    val unit: String? = null,
    val expectedRange: ClosedFloatingPointRange<Double>? = null,
    val staleAfterMs: Long? = null,
    val confidence: Confidence = Confidence.HYP,
    val notes: String? = null,
)

data class SignalSample<T>(
    val signalId: String,
    val value: T?,
    val rawValue: String?,
    val availability: Availability,
    val source: SourceKind,
    val wallClockMs: Long,
    val elapsedRealtimeNanos: Long,
) {
    val isKnown: Boolean
        get() = value != null &&
            (availability == Availability.VALID ||
                availability == Availability.OBSERVED ||
                availability == Availability.STALE)
}

// ------------------------------------------------------------------- extras model

/**
 * One extra of a broadcast, captured with its real Java type.
 *
 * Byte arrays keep length + hex + Base64 so a payload can be analysed later without
 * the original device.
 */
data class CapturedExtra(
    val name: String,
    val javaType: String,
    val value: String?,
    val length: Int? = null,
    val hex: String? = null,
    val base64: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("javaType", javaType)
        value?.let { put("value", it) }
        length?.let { put("length", it) }
        hex?.let { put("hex", it) }
        base64?.let { put("base64", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = CapturedExtra(
            name = o.getString("name"),
            javaType = o.optString("javaType", "?"),
            value = o.optString("value", "").takeIf { it.isNotEmpty() },
            length = if (o.has("length")) o.getInt("length") else null,
            hex = o.optString("hex", "").takeIf { it.isNotEmpty() },
            base64 = o.optString("base64", "").takeIf { it.isNotEmpty() },
        )
    }
}

/** Head-unit context attached to every event so nothing needs to be correlated by hand. */
data class SourceState(
    val source: Int? = null,
    val reverse: Boolean? = null,
    val topPackage: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        source?.let { put("source", it) }
        reverse?.let { put("reverse", it) }
        topPackage?.let { put("topPackage", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) SourceState() else SourceState(
            source = if (o.has("source")) o.getInt("source") else null,
            reverse = if (o.has("reverse")) o.getBoolean("reverse") else null,
            topPackage = o.optString("topPackage", "").takeIf { it.isNotEmpty() },
        )
    }
}

// --------------------------------------------------------------------- events

enum class EventKind {
    BROADCAST, PROPERTY_CHANGE, SETTINGS_CHANGE, AUDIO_SNAPSHOT, AUDIO_CHANGE,
    KEY_EVENT, MEDIA_STATE, MARKER, SESSION_META, NOTE
}

/** Payloads are sealed so the writer and the replay engine stay in sync. */
sealed interface EventPayload {
    fun toJson(): JSONObject

    data class Broadcast(
        val action: String,
        val catalogStatus: CatalogStatus,
        val extras: List<CapturedExtra>,
        val unexpectedExtras: List<String> = emptyList(),
        val typeMismatches: List<String> = emptyList(),
        val senderPackage: String? = null,
    ) : EventPayload {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", action)
            put("catalogStatus", catalogStatus.name)
            put("extras", JSONArray().also { a -> extras.forEach { a.put(it.toJson()) } })
            if (unexpectedExtras.isNotEmpty())
                put("unexpectedExtras", JSONArray(unexpectedExtras))
            if (typeMismatches.isNotEmpty())
                put("typeMismatches", JSONArray(typeMismatches))
            senderPackage?.let { put("senderPackage", it) }
        }
    }

    data class KeyValueChange(
        val kind: EventKind,
        val key: String,
        val namespace: String?,
        val old: String?,
        val new: String?,
    ) : EventPayload {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("key", key)
            namespace?.let { put("namespace", it) }
            put("old", old ?: JSONObject.NULL)
            put("new", new ?: JSONObject.NULL)
        }
    }

    data class AudioSnapshotPayload(val snapshot: AudioSnapshot) : EventPayload {
        override fun toJson(): JSONObject = snapshot.toJson()
    }

    data class KeyEventPayload(
        val origin: String,          // "WITS_BROADCAST" | "ANDROID_FOCUSED"
        val rawCode: Int?,
        val androidKeyCode: Int?,
        val action: String?,         // down/up/repeat
        val repeatCount: Int? = null,
    ) : EventPayload {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("origin", origin)
            rawCode?.let { put("rawCode", it) }
            androidKeyCode?.let { put("androidKeyCode", it) }
            action?.let { put("action", it) }
            repeatCount?.let { put("repeatCount", it) }
        }
    }

    data class MarkerPayload(val marker: MarkerRecord) : EventPayload {
        override fun toJson(): JSONObject = marker.toJson()
    }

    data class Note(val text: String) : EventPayload {
        override fun toJson(): JSONObject = JSONObject().apply { put("text", text) }
    }

    data class Raw(val json: JSONObject) : EventPayload {
        override fun toJson(): JSONObject = json
    }
}

data class SessionEvent(
    val seq: Long,
    val wallClockMs: Long,
    val elapsedRealtimeNanos: Long,
    val kind: EventKind,
    val sourceState: SourceState,
    val payload: EventPayload,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("seq", seq)
        put("wallClockMs", wallClockMs)
        put("elapsedRealtimeNanos", elapsedRealtimeNanos)
        put("kind", kind.name)
        put("sourceState", sourceState.toJson())
        put("payload", payload.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): SessionEvent = SessionEvent(
            seq = o.getLong("seq"),
            wallClockMs = o.getLong("wallClockMs"),
            elapsedRealtimeNanos = o.getLong("elapsedRealtimeNanos"),
            kind = runCatching { EventKind.valueOf(o.getString("kind")) }
                .getOrDefault(EventKind.NOTE),
            sourceState = SourceState.fromJson(o.optJSONObject("sourceState")),
            payload = EventPayload.Raw(o.optJSONObject("payload") ?: JSONObject()),
        )
    }
}

// --------------------------------------------------------------------- markers

enum class MarkerType {
    ANDROID_SPOTIFY_START,
    OEM_RADIO_START,
    OEM_BLUETOOTH_START,
    ZLINK_START,
    NBT_KNOB_VOLUME_UP,
    NBT_KNOB_VOLUME_DOWN,
    NBT_KNOB_MUTE,
    STEERING_VOLUME_UP,
    STEERING_VOLUME_DOWN,
    STEERING_MUTE,
    IDRIVE_ROTATE_LEFT,
    IDRIVE_ROTATE_RIGHT,
    IDRIVE_PRESS,
    SOURCE_OEM_TO_ANDROID,
    SOURCE_ANDROID_TO_OEM,
    REVERSE_START,
    REVERSE_END,
    CUSTOM,
}

enum class Tristate { YES, NO, UNKNOWN }
enum class AudibleChange { UP, DOWN, NO_CHANGE, UNCLEAR }

data class UserObservations(
    val oemOsdVisible: Tristate = Tristate.UNKNOWN,
    val androidOsdVisible: Tristate = Tristate.UNKNOWN,
    val audibleChange: AudibleChange = AudibleChange.UNCLEAR,
    val bothDomainsChanged: Tristate = Tristate.UNKNOWN,
    val sourceChangedUnexpectedly: Tristate = Tristate.UNKNOWN,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("oemOsdVisible", oemOsdVisible.name)
        put("androidOsdVisible", androidOsdVisible.name)
        put("audibleChange", audibleChange.name)
        put("bothDomainsChanged", bothDomainsChanged.name)
        put("sourceChangedUnexpectedly", sourceChangedUnexpectedly.name)
    }
}

data class MarkerRecord(
    val markerType: MarkerType,
    val note: String = "",
    val preWindowMs: Long,
    val postWindowMs: Long,
    val preSeqFrom: Long,
    var postSeqTo: Long = -1,
    var userObservations: UserObservations = UserObservations(),
    val steeringSchemeRaw: String? = null,
    val sourceAtMarker: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("markerType", markerType.name)
        put("note", note)
        put("preWindowMs", preWindowMs)
        put("postWindowMs", postWindowMs)
        put("preSeqFrom", preSeqFrom)
        put("postSeqTo", postSeqTo)
        put("userObservations", userObservations.toJson())
        steeringSchemeRaw?.let { put("steeringSchemeRaw", it) }
        sourceAtMarker?.let { put("sourceAtMarker", it) }
    }
}

// -------------------------------------------------------------------- metadata

data class DeviceInfo(
    val buildDisplayId: String?,
    val fingerprint: String?,
    val sdkInt: Int,
    val witsProductId: String?,
    val witsModel: String?,
    val mcuVersion: String?,
    val mcuCanVersion: String?,
    val displayWidth: Int,
    val displayHeight: Int,
    val density: Float,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("buildDisplayId", buildDisplayId ?: JSONObject.NULL)
        put("fingerprint", fingerprint ?: JSONObject.NULL)
        put("sdkInt", sdkInt)
        put("witsProductId", witsProductId ?: JSONObject.NULL)
        put("witsModel", witsModel ?: JSONObject.NULL)
        put("mcuVersion", mcuVersion ?: JSONObject.NULL)
        put("mcuCanVersion", mcuCanVersion ?: JSONObject.NULL)
        put("displayWidth", displayWidth)
        put("displayHeight", displayHeight)
        put("density", density.toDouble())
    }
}

data class SessionMetadata(
    val sessionId: String,
    val startedWallClockMs: Long,
    val startedElapsedRealtimeNanos: Long,
    val appVersion: String,
    val catalogVersion: Int,
    val otaPhase: OtaPhase,
    val device: DeviceInfo,
    val steeringSchemeKey: String = "control_type",
    val steeringSchemeRaw: String? = null,
    val steeringSchemeLabel: String? = null,
    val sourceAtStart: Int? = null,
    val packages: Map<String, String> = emptyMap(),
    val userContext: String = "",
    val propertyStrategy: String = "UNKNOWN",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("startedWallClockMs", startedWallClockMs)
        put("startedElapsedRealtimeNanos", startedElapsedRealtimeNanos)
        put("appVersion", appVersion)
        put("catalogVersion", catalogVersion)
        put("otaPhase", otaPhase.name)
        put("device", device.toJson())
        put("steeringScheme", JSONObject().apply {
            put("key", steeringSchemeKey)
            put("rawValue", steeringSchemeRaw ?: JSONObject.NULL)
            put("label", steeringSchemeLabel ?: JSONObject.NULL)
        })
        sourceAtStart?.let { put("sourceAtStart", it) }
        put("packages", JSONObject(packages as Map<*, *>))
        put("userContext", userContext)
        put("propertyStrategy", propertyStrategy)
    }
}

// -------------------------------------------------------------- audio snapshot

data class StreamState(
    val stream: Int,
    val name: String,
    val volume: Int,
    val max: Int,
    val min: Int,
    val db: Float?,
    val muted: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stream", stream); put("name", name)
        put("volume", volume); put("max", max); put("min", min)
        db?.let { put("db", it.toDouble()) }
        put("muted", muted)
    }
}

data class AudioSnapshot(
    val reason: String,
    val wallClockMs: Long = System.currentTimeMillis(),
    val elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    val streams: List<StreamState> = emptyList(),
    val ringerMode: Int? = null,
    val audioMode: Int? = null,
    val micMuted: Boolean? = null,
    val musicActive: Boolean? = null,
    val outputDevices: List<String> = emptyList(),
    val activePlayback: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("reason", reason)
        put("wallClockMs", wallClockMs)
        put("elapsedRealtimeNanos", elapsedRealtimeNanos)
        put("streams", JSONArray().also { a -> streams.forEach { a.put(it.toJson()) } })
        ringerMode?.let { put("ringerMode", it) }
        audioMode?.let { put("audioMode", it) }
        micMuted?.let { put("micMuted", it) }
        musicActive?.let { put("musicActive", it) }
        if (outputDevices.isNotEmpty()) put("outputDevices", JSONArray(outputDevices))
        if (activePlayback.isNotEmpty()) put("activePlayback", JSONArray(activePlayback))
    }

    /** Per-stream deltas against an earlier snapshot; only streams that moved. */
    fun deltaFrom(previous: AudioSnapshot): List<String> {
        val prev = previous.streams.associateBy { it.stream }
        return streams.mapNotNull { now ->
            val before = prev[now.stream] ?: return@mapNotNull null
            when {
                before.volume != now.volume ->
                    "${now.name}: ${before.volume} -> ${now.volume} (max ${now.max})"
                before.muted != now.muted ->
                    "${now.name}: muted ${before.muted} -> ${now.muted}"
                else -> null
            }
        }
    }
}

// --------------------------------------------------------------- volume domains

/**
 * The four domains from docs/audio-volume.md §7. The UI must always label which one it
 * is showing, and must never print a number for a domain it has not read.
 */
enum class VolumeDomain(val label: String) {
    ANDROID_MEDIA("Android media"),
    ANDROID_OTHER("Android (other stream)"),
    WITS_MCU("Wits/MCU"),
    OEM_NBT("OEM BMW/NBT"),
    OEM_RELATIVE_ESTIMATE("OEM (estimated, relative)"),
}

data class VolumeReading(
    val domain: VolumeDomain,
    val value: Int?,
    val max: Int?,
    val availability: Availability,
    val rawValue: String? = null,
) {
    /**
     * Truthful rendering. An unread domain is never "0".
     * The OEM domain never shows a number unless an absolute source has been proven.
     */
    fun display(): String = when {
        domain == VolumeDomain.OEM_NBT && availability != Availability.VALID ->
            "value unavailable"
        availability == Availability.UNSUPPORTED -> "n/a"
        availability == Availability.UNKNOWN || value == null -> "—"
        domain == VolumeDomain.OEM_RELATIVE_ESTIMATE ->
            "≈ ${if (value >= 0) "+" else ""}$value steps since reset (estimate)"
        max != null -> "$value / $max"
        else -> value.toString()
    }
}
