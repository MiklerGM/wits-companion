package io.github.miklergm.witscompanion.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the JSONL event log through the Storage Access Framework, so the app
 * needs no storage permission and the user picks the destination.
 */
object LogExportHelper {

    const val REQUEST_CODE = 4711
    const val REQUEST_CODE_TEXT = 4712

    fun export(activity: MainActivity, app: WitsCompanionApp) {
        val name = "wits-companion-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { activity.startActivityForResult(intent, REQUEST_CODE) }
            .onFailure { activity.toast("No file picker available") }
    }

    private var pendingText: String? = null

    /** Exports arbitrary text (e.g. a Signal Explorer session bundle) via SAF. */
    fun exportText(activity: MainActivity, text: String, fileName: String) {
        pendingText = text
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        runCatching { activity.startActivityForResult(intent, REQUEST_CODE_TEXT) }
            .onFailure { activity.toast("No file picker available") }
    }

    fun writeText(activity: Activity, uri: Uri): Boolean = runCatching {
        val body = pendingText ?: return false
        activity.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
        pendingText = null
        true
    }.getOrDefault(false)

    /** Called from MainActivity.onActivityResult. */
    fun write(activity: Activity, app: WitsCompanionApp, uri: Uri): Boolean = runCatching {
        activity.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(app.eventLogger.readAll().toByteArray())
        }
        true
    }.getOrDefault(false)
}
