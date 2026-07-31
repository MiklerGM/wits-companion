package io.github.miklergm.privprobe

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * Reports what a platform-signed APK is actually granted on this head unit.
 *
 * **Read-only by construction.** It resolves permissions, compares signatures and makes
 * one read call into ActivityTaskManager. It never resizes a task, moves a window,
 * switches a source or writes a setting — the answer it produces is what decides whether
 * doing any of that is worth pursuing, and the probe must not change the thing it
 * measures.
 */
class ProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(24, 24, 24, 24)
            text = report()
        }
        setContentView(ScrollView(this).apply { addView(out) })
    }

    private fun report(): String = buildString {
        appendLine("=== identity ===")
        appendLine("package    : $packageName")
        appendLine("uid        : ${applicationInfo.uid}")
        appendLine("sharedUser : ${runCatching { packageManager.getPackageInfo(packageName, 0).sharedUserId }.getOrNull() ?: "—"}")
        appendLine()

        appendLine("=== signed with the platform key? ===")
        appendLine(platformSignatureCheck())
        appendLine()

        appendLine("=== permissions ===")
        PERMISSIONS.forEach { p ->
            val granted = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            appendLine("%-38s %s".format(p.removePrefix("android.permission."), if (granted) "GRANTED" else "denied"))
        }
        appendLine()

        appendLine("=== hidden API reachable? ===")
        appendLine(hiddenApiCheck())
        appendLine()

        appendLine("=== ActivityTaskManager read call ===")
        appendLine(taskManagerCheck())
    }

    /**
     * Compares our signing certificate with the one on `android` (framework-res). Equal
     * certificates are what grants `signature`-level permissions.
     */
    private fun platformSignatureCheck(): String = runCatching {
        val flag = PackageManager.GET_SIGNING_CERTIFICATES
        fun certs(pkg: String) = packageManager.getPackageInfo(pkg, flag)
            .signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()

        val ours = certs(packageName)
        val platform = certs("android")
        when {
            ours.isEmpty() || platform.isEmpty() -> "could not read certificates"
            ours == platform -> "YES — identical to the certificate on \"android\""
            ours.intersect(platform).isNotEmpty() -> "partial overlap with \"android\""
            else -> "no — different certificate from \"android\""
        }
    }.getOrElse { "error: ${it.javaClass.simpleName}: ${it.message}" }

    /**
     * Platform-signed apps are exempt from the hidden-API blocklist. If this resolves, the
     * companion could call `ActivityOptions.setLaunchWindowingMode` directly instead of
     * reflectively and hoping.
     */
    private fun hiddenApiCheck(): String = runCatching {
        val m = android.app.ActivityOptions::class.java
            .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
        "setLaunchWindowingMode resolved: ${m.name}"
    }.getOrElse { "blocked or missing: ${it.javaClass.simpleName}" }

    /**
     * One read-only call. `getAllRootTaskInfos` is what would give the companion real
     * feedback: today it broadcasts and cannot tell whether anything moved.
     */
    private fun taskManagerCheck(): String = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager")
        val service = atm.getMethod("getService").invoke(null)
            ?: return "getService() returned null"
        val infos = service.javaClass.getMethod("getAllRootTaskInfos")
            .invoke(service) as? List<*>
        buildString {
            appendLine("getAllRootTaskInfos() -> ${infos?.size ?: 0} root tasks")
            infos?.take(6)?.forEach { appendLine("  " + it.toString().take(150)) }
        }
    }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}" }

    private companion object {
        val PERMISSIONS = listOf(
            "android.permission.MANAGE_ACTIVITY_TASKS",
            "android.permission.START_TASKS_FROM_RECENTS",
            "android.permission.INTERNAL_SYSTEM_WINDOW",
            "android.permission.STATUS_BAR_SERVICE",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.REAL_GET_TASKS",
        )
    }
}
