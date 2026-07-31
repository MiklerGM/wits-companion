package io.github.miklergm.witscompanion.layout

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.Settings
import io.github.miklergm.witscompanion.wits.WitsPackages
import io.github.miklergm.witscompanion.wits.WitsSettingsKeys

/**
 * The set of apps a layout slot can hold, and the vendor's own choices to pre-fill them.
 *
 * Two sources, combined:
 *  - every installed app with a launcher activity, enumerated through the `<queries>`
 *    LAUNCHER intent (so no `QUERY_ALL_PACKAGES`);
 *  - the apps the user already picked in the vendor system settings, read from
 *    `Settings.System` — reading needs no permission, and it means the companion offers
 *    "your music app" and "your nav app" without asking again.
 *
 * This replaces the hard-coded Maps/Chrome/Spotify triple: a slot is now whatever the
 * user chooses, seeded with what the vehicle already knows.
 */
class AppCatalog(private val context: Context) {

    data class AppEntry(val packageName: String, val label: String) {
        fun icon(context: Context): Drawable? =
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    /** Installed launchable apps, sorted by label, self excluded. */
    fun launchableApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .map { it.activityInfo.packageName }
                .filter { it != context.packageName }
                .distinct()
                .map { pkg ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    AppEntry(pkg, label)
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }.getOrDefault(emptyList())
    }

    /**
     * The vendor's chosen apps, read from `Settings.System`. Any of these can be null if
     * the user has not picked one. Packages that turn out not to be installed are dropped.
     */
    fun vendorDefaults(): VendorDefaults {
        fun read(key: String): String? =
            runCatching { Settings.System.getString(context.contentResolver, key) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && isInstalled(it) }

        return VendorDefaults(
            music = read(WitsSettingsKeys.THIRD_APP_MUSIC_PKG) ?: read(WitsSettingsKeys.MUSIC_APP),
            video = read(WitsSettingsKeys.THIRD_APP_VIDEO_PKG),
            navigation = read(WitsSettingsKeys.NAVI_APP),
            voice = read(WitsSettingsKeys.THIRD_APP_VOICE_PKG),
        )
    }

    /**
     * Suggested packages to seed a slot picker with, most useful first: the vendor's
     * nav/music/video choices, then the well-known apps, then anything else installed.
     * Deduplicated, only installed apps.
     */
    fun suggestedPackages(): List<String> {
        val d = vendorDefaults()
        val preferred = listOfNotNull(
            d.navigation, d.music, d.video,
            WitsPackages.MAPS, WitsPackages.SPOTIFY, WitsPackages.CHROME,
        )
        val rest = launchableApps().map { it.packageName }
        return (preferred + rest).filter { isInstalled(it) }.distinct()
    }

    fun labelFor(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName.substringAfterLast('.'))

    fun isInstalled(packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    data class VendorDefaults(
        val music: String?,
        val video: String?,
        val navigation: String?,
        val voice: String?,
    ) {
        val any: Boolean get() = listOfNotNull(music, video, navigation, voice).isNotEmpty()
    }
}
