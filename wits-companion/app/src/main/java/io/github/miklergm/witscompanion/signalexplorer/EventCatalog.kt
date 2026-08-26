package io.github.miklergm.witscompanion.signalexplorer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The versioned list of actions/properties/settings the Explorer knows about.
 *
 * Android gives a normal app no wildcard broadcast subscription, so we can only observe
 * actions we name explicitly. The catalog is that list — loaded from
 * `assets/signal-catalog.json`, overridable by an imported file without rebuilding.
 *
 * See docs/signal-catalog.md.
 */
class EventCatalog private constructor(
    val version: Int,
    val generatedFrom: String,
    val broadcasts: List<BroadcastDef>,
    val properties: List<PropertyDef>,
    val settingsKeys: List<SettingsDef>,
) {

    data class ExtraDef(val name: String, val type: ValueType, val meaning: String?)

    data class BroadcastDef(
        val action: String,
        val status: CatalogStatus,
        val confidence: Confidence,
        val evidence: String?,
        val extras: List<ExtraDef>,
        val note: String?,
    )

    data class PropertyDef(
        val name: String,
        val type: ValueType,
        val confidence: Confidence,
        val evidence: String?,
        /** TARGETED = sampled every tick; SNAPSHOT = only at session start/marker/end. */
        val sampling: String,
    )

    data class SettingsDef(
        val key: String,
        val namespace: String,
        val type: ValueType,
        val meaning: String?,
        val confidence: Confidence,
        val evidence: String?,
    )

    private val byAction: Map<String, BroadcastDef> = broadcasts.associateBy { it.action }

    /** Actions we subscribe to. `UNSUPPORTED` entries are skipped. */
    fun subscribableActions(): List<String> =
        broadcasts.filter { it.status != CatalogStatus.UNSUPPORTED }.map { it.action }

    fun targetedProperties(): List<String> =
        properties.filter { it.sampling.equals("TARGETED", true) }.map { it.name }

    fun snapshotProperties(): List<String> = properties.map { it.name }

    fun watchedSettings(namespace: String): List<String> =
        settingsKeys.filter { it.namespace.equals(namespace, true) }.map { it.key }

    fun statusOf(action: String): CatalogStatus =
        byAction[action]?.status ?: CatalogStatus.UNCATALOGUED

    fun definitionOf(action: String): BroadcastDef? = byAction[action]

    /**
     * Compares observed extras with the catalog.
     *
     * @return unexpected extra names and type mismatches (`name: expected!=actual`)
     */
    fun checkExtras(action: String, observed: List<CapturedExtra>): Pair<List<String>, List<String>> {
        val def = byAction[action] ?: return observed.map { it.name } to emptyList()
        val expected = def.extras.associateBy { it.name }
        val unexpected = observed.filter { it.name !in expected }.map { it.name }
        val mismatches = observed.mapNotNull { got ->
            val want = expected[got.name] ?: return@mapNotNull null
            val actual = javaTypeToValueType(got.javaType)
            if (want.type != ValueType.UNKNOWN && actual != ValueType.UNKNOWN &&
                !typesCompatible(want.type, actual)
            ) "${got.name}: expected ${want.type} got $actual (${got.javaType})" else null
        }
        return unexpected to mismatches
    }

    companion object {
        private const val TAG = "WitsEventCatalog"
        const val ASSET_NAME = "signal-catalog.json"
        const val IMPORT_FILE = "imported-signal-catalog.json"

        /** Imported file wins over the bundled asset. */
        fun load(context: Context): EventCatalog {
            val imported = File(context.filesDir, IMPORT_FILE)
            if (imported.exists()) {
                runCatching { return parse(imported.readText()) }
                    .onFailure { Log.w(TAG, "imported catalog unusable: ${it.message}") }
            }
            return runCatching {
                parse(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
            }.getOrElse {
                Log.e(TAG, "bundled catalog unusable: ${it.message}")
                EventCatalog(0, "empty", emptyList(), emptyList(), emptyList())
            }
        }

        fun saveImported(context: Context, json: String): Result<EventCatalog> = runCatching {
            val parsed = parse(json)                       // validate before persisting
            File(context.filesDir, IMPORT_FILE).writeText(json)
            parsed
        }

        fun clearImported(context: Context) {
            runCatching { File(context.filesDir, IMPORT_FILE).delete() }
        }

        fun parse(json: String): EventCatalog {
            val root = JSONObject(json)
            return EventCatalog(
                version = root.optInt("catalogVersion", 0),
                generatedFrom = root.optString("generatedFrom", "?"),
                broadcasts = root.optJSONArray("broadcasts").mapObjects { o ->
                    BroadcastDef(
                        action = o.getString("action"),
                        status = enumOr(o.optString("status"), CatalogStatus.EXPERIMENTAL),
                        confidence = enumOr(o.optString("confidence"), Confidence.HYP),
                        evidence = o.optString("evidence", "").ifEmpty { null },
                        note = o.optString("note", "").ifEmpty { null },
                        extras = o.optJSONArray("extras").mapObjects { e ->
                            ExtraDef(
                                name = e.getString("name"),
                                type = enumOr(e.optString("type"), ValueType.UNKNOWN),
                                meaning = e.optString("meaning", "").ifEmpty { null },
                            )
                        },
                    )
                },
                properties = root.optJSONArray("properties").mapObjects { o ->
                    PropertyDef(
                        name = o.getString("name"),
                        type = enumOr(o.optString("type"), ValueType.UNKNOWN),
                        confidence = enumOr(o.optString("confidence"), Confidence.HYP),
                        evidence = o.optString("evidence", "").ifEmpty { null },
                        sampling = o.optString("sampling", "SNAPSHOT"),
                    )
                },
                settingsKeys = root.optJSONArray("settingsKeys").mapObjects { o ->
                    SettingsDef(
                        key = o.getString("key"),
                        namespace = o.optString("namespace", "system"),
                        type = enumOr(o.optString("type"), ValueType.UNKNOWN),
                        meaning = o.optString("meaning", "").ifEmpty { null },
                        confidence = enumOr(o.optString("confidence"), Confidence.HYP),
                        evidence = o.optString("evidence", "").ifEmpty { null },
                    )
                },
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(s: String?, fallback: T): T =
            runCatching { enumValueOf<T>(s!!.uppercase()) }.getOrDefault(fallback)

        private fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { i ->
                runCatching { block(getJSONObject(i)) }.getOrNull()
            }
        }

        fun javaTypeToValueType(javaType: String): ValueType = when {
            javaType.endsWith("Boolean") -> ValueType.BOOL_INT
            javaType.endsWith("Integer") -> ValueType.INT
            javaType.endsWith("Long") -> ValueType.LONG
            javaType.endsWith("Float") || javaType.endsWith("Double") -> ValueType.FLOAT
            javaType.endsWith("String") -> ValueType.STRING
            javaType == "byte[]" -> ValueType.BYTES
            else -> ValueType.UNKNOWN
        }

        /** INT-ish catalog types accept a Boolean or Integer on the wire. */
        private fun typesCompatible(expected: ValueType, actual: ValueType): Boolean {
            val intish = setOf(
                ValueType.INT, ValueType.BOOL_INT, ValueType.INT_PACKED,
                ValueType.INT_UNKNOWN_SCALE, ValueType.LONG,
            )
            val floatish = setOf(ValueType.FLOAT, ValueType.FLOAT_UNKNOWN_SCALE)
            return when {
                expected == actual -> true
                expected in intish && actual in intish -> true
                expected in floatish && actual in floatish -> true
                expected in intish && actual == ValueType.BOOL_INT -> true
                else -> false
            }
        }
    }
}

/**
 * Which catalogued actions actually fired during a session.
 *
 * Every set here is **bounded**. The keys come from broadcast extras, which the sender chooses,
 * and an indexed array alone produces one distinct `name:type` per element. This accumulates for
 * the whole session and is not part of the ring, so the ring's 2000 events and the file's 32 MB
 * bound nothing about it: a single chatty sender could grow the delta until the process died,
 * with every per-event cap respected.
 */
class CatalogDelta {
    private val seen = LinkedHashMap<String, MutableSet<String>>()   // action -> extra "name:type"
    private val unexpected = LinkedHashMap<String, MutableSet<String>>()
    private val mismatches = LinkedHashMap<String, MutableSet<String>>()

    /** True when a limit was hit, so the export can say the summary is partial. */
    var truncated: Boolean = false
        private set

    private fun MutableSet<String>.addBounded(values: Iterable<String>) {
        values.forEach { v ->
            if (size >= MAX_KEYS_PER_ACTION) { truncated = true; return }
            add(v)
        }
    }

    private fun LinkedHashMap<String, MutableSet<String>>.setFor(action: String): MutableSet<String>? {
        this[action]?.let { return it }
        if (size >= MAX_ACTIONS) { truncated = true; return null }
        return linkedSetOf<String>().also { this[action] = it }
    }

    fun record(action: String, extras: List<CapturedExtra>, unexp: List<String>, mism: List<String>) {
        seen.setFor(action)?.addBounded(extras.map { "${it.name}:${it.javaType}" })
        if (unexp.isNotEmpty()) unexpected.setFor(action)?.addBounded(unexp)
        if (mism.isNotEmpty()) mismatches.setFor(action)?.addBounded(mism)
    }

    fun toJson(catalog: EventCatalog): JSONObject {
        val never = catalog.subscribableActions().filter { it !in seen }
        return JSONObject().apply {
            put("catalogVersion", catalog.version)
            put("actionsSeen", JSONObject().also { o ->
                seen.forEach { (a, extras) -> o.put(a, JSONArray(extras.toList())) }
            })
            put("actionsNeverSeen", JSONArray(never))
            put("unexpectedExtras", JSONObject().also { o ->
                unexpected.forEach { (a, e) -> o.put(a, JSONArray(e.toList())) }
            })
            put("typeMismatches", JSONObject().also { o ->
                mismatches.forEach { (a, e) -> o.put(a, JSONArray(e.toList())) }
            })
            if (truncated) put("truncated", "limits reached; this summary is partial")
        }
    }

    fun seenCount(): Int = seen.size

    companion object {
        /** Distinct extra keys kept per action. Far above any real vendor broadcast. */
        const val MAX_KEYS_PER_ACTION = 256

        /** Distinct actions kept. The catalogue itself is a fraction of this. */
        const val MAX_ACTIONS = 512
    }
}
