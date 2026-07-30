package io.github.miklergm.witscompanion.logging

import android.content.Context
import android.os.SystemClock
import android.util.Log
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

    fun isSensitiveKey(key: String): Boolean {
        val k = key.lowercase()
        return SENSITIVE_KEYS.any { k.contains(it) }
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
        val isMedia = k.lowercase().let { it.contains("title") || it.contains("artist") || it.contains("album") }
        when {
            isMedia && !verboseMedia -> k to "<redacted>"
            isSensitiveKey(k) && !(isMedia && verboseMedia) -> k to "<redacted>"
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
