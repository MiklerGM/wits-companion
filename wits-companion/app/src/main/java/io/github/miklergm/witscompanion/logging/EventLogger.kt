package io.github.miklergm.witscompanion.logging

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Removes vehicle- and user-identifying data from anything we persist.
 *
 * See docs/security.md §3.7. Firmware constants are kept; identifiers are not.
 */
object LogRedactor {

    private val MAC = Regex("""\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")
    private val VIN = Regex("""\b[A-HJ-NPR-Z0-9]{17}\b""")
    private val LONG_DIGITS = Regex("""\b\d{15,}\b""")   // IMEI/IMSI/ICCID-shaped

    private val SENSITIVE_KEYS = listOf(
        "serial", "serialno", "imei", "imsi", "iccid", "meid", "subscriber",
        "ssid", "psk", "passphrase", "password", "passwd",
        "bt_name", "btmac", "bluetooth", "phonename", "phone_name",
        "vin", "latitude", "longitude", "location",
        "title", "artist", "album",   // media metadata: private by default
    )

    /** Media metadata is sensitive by default but can be kept with an explicit debug opt-in. */
    private val MEDIA_KEYS = listOf("title", "artist", "album")

    /** The payload fields of a [CapturedExtra]; its real key lives in a sibling "name". */
    private val EXTRA_PAYLOAD_KEYS = setOf("value", "hex", "base64")

    const val REDACTED = "<redacted>"

    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return SENSITIVE_KEYS.any { k.contains(it) }
    }

    private fun isMediaKey(key: String): Boolean =
        key.lowercase().let { k -> MEDIA_KEYS.any { k.contains(it) } }

    /** Whether a key's value must be dropped, honouring the media opt-in. */
    fun isRedactedKey(key: String, verboseMedia: Boolean = false): Boolean =
        if (isMediaKey(key)) !verboseMedia else isSensitiveKey(key)

    /**
     * Key-aware redaction over a whole JSON tree — the form anything persisted must go through.
     *
     * [redactValue] alone is not enough: it only matches MAC/VIN/long-digit *shapes*, so an SSID,
     * a paired phone name or a track title passes straight through it. Those are caught by key,
     * which means the walk has to see the keys — serializing first and regexing the string loses
     * them.
     *
     * Two rules, because captured broadcast extras do not nest their key as a JSON key:
     *
     *  1. Any key that [isRedactedKey] accepts has its value replaced, however deep.
     *  2. A [CapturedExtra] object serializes as `{"name":"ssid","javaType":…,"value":"…"}` — its
     *     real key is *data*, not a key. When "name" names something sensitive, the sibling
     *     payload fields (`value`, `hex`, `base64`) are dropped with it. Without this a vendor
     *     broadcast carrying an SSID or a phone name would be recorded verbatim.
     */
    fun redactJson(value: Any?, verboseMedia: Boolean = false): Any? = when (value) {
        is JSONObject -> redactJsonObject(value, verboseMedia)
        is JSONArray -> JSONArray().also { out ->
            for (i in 0 until value.length()) out.put(redactJson(value.opt(i), verboseMedia))
        }
        is String -> redactValue(value)
        else -> value
    }

    private fun redactJsonObject(o: JSONObject, verboseMedia: Boolean): JSONObject {
        // Rule 2: only for the captured-extra shape, identified by its javaType companion, so
        // an unrelated object with a "name" field is not over-redacted.
        val extraName = o.optString("name", "").takeIf { it.isNotEmpty() && o.has("javaType") }
        val extraIsSensitive = extraName != null && isRedactedKey(extraName, verboseMedia)

        val out = JSONObject()
        o.keys().forEach { key ->
            val child = o.opt(key)
            out.put(
                key,
                when {
                    isRedactedKey(key, verboseMedia) -> REDACTED
                    extraIsSensitive && key in EXTRA_PAYLOAD_KEYS -> REDACTED
                    else -> redactJson(child, verboseMedia)
                },
            )
        }
        return out
    }

    fun redactValue(value: String): String =
        value
            .replace(MAC, "<MAC>")
            .replace(VIN, "<VIN>")
            .replace(LONG_DIGITS, "<ID>")

    /**
     * @param verboseMedia when true, media titles are kept (explicit debug opt-in)
     */
    fun redactExtras(
        extras: Map<String, Any?>,
        verboseMedia: Boolean = false,
    ): Map<String, String> = extras.mapNotNull { (k, v) ->
        when {
            isRedactedKey(k, verboseMedia) -> k to REDACTED
            v == null -> k to "null"
            else -> k to redactValue(v.toString())
        }
    }.toMap()
}

/**
 * Local, append-only JSON Lines event log.
 *
 * Never leaves the device on its own — the app has no INTERNET permission. Export is
 * user-initiated through the Storage Access Framework.
 */
class EventLogger(
    context: Context,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val appContext = context.applicationContext
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "wits-eventlog") }
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    /** Opt-in: keeps media metadata in the log. Off by default. */
    @Volatile
    var verboseMedia: Boolean = false

    val logFile: File get() = File(appContext.filesDir, FILE_NAME)

    fun log(
        category: String,
        action: String,
        packageName: String? = null,
        source: String? = null,
        extras: Map<String, Any?> = emptyMap(),
        result: String? = null,
        confidence: String? = null,
    ) {
        val wallClock = iso.format(Date())
        val elapsed = SystemClock.elapsedRealtime()
        val safeExtras = LogRedactor.redactExtras(extras, verboseMedia)

        io.execute {
            runCatching {
                val o = JSONObject().apply {
                    put("timestamp", wallClock)
                    put("elapsedRealtime", elapsed)
                    put("category", category)
                    put("action", action)
                    packageName?.let { put("package", it) }
                    source?.let { put("source", it) }
                    result?.let { put("result", it) }
                    confidence?.let { put("confidence", it) }
                    if (safeExtras.isNotEmpty()) {
                        put("extras", JSONObject(safeExtras as Map<*, *>))
                    }
                }
                rotateIfNeeded()
                logFile.appendText(o.toString() + "\n")
            }.onFailure { Log.w(TAG, "log write failed: ${it.message}") }
        }
    }

    private fun rotateIfNeeded() {
        val f = logFile
        if (f.exists() && f.length() > maxBytes) {
            val backup = File(appContext.filesDir, "$FILE_NAME.1")
            runCatching {
                if (backup.exists()) backup.delete()
                f.renameTo(backup)
            }
        }
    }

    fun readAll(): String = runCatching { logFile.readText() }.getOrDefault("")

    fun lineCount(): Int = runCatching {
        if (!logFile.exists()) 0 else logFile.useLines { it.count() }
    }.getOrDefault(0)

    fun clear() {
        io.execute { runCatching { logFile.delete() } }
    }

    companion object {
        private const val TAG = "WitsEventLogger"
        const val FILE_NAME = "wits-companion-events.jsonl"
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
    }
}
