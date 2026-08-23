package io.github.miklergm.witscompanion.nav

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The next navigation instruction, for the Cockpit panel.
 *
 * Nothing is fabricated: a field is null when the notification did not carry it, and the panel
 * shows nothing at all rather than an empty row. `[available] == false` means no navigation is
 * running, which is different from "running but we could not read it".
 */
data class NavigationSnapshot(
    val available: Boolean = false,
    val packageName: String? = null,
    /** The manoeuvre, e.g. "Turn right onto Bornholmer Straße". */
    val instruction: String? = null,
    /** Distance to it, e.g. "300 m" — whatever the app wrote, not reformatted. */
    val distance: String? = null,
    /** ETA / remaining, when the notification carries it. */
    val eta: String? = null,
) {
    val hasInstruction: Boolean get() = available && !instruction.isNullOrBlank()
}

/**
 * Reads the turn-by-turn instruction from the navigation app's own notification.
 *
 * ## Why a notification
 *
 * There is no other route on this platform. Google exposes no public API for the current
 * manoeuvre; the Android Auto protocol is not open to us and we are not a projection host; the
 * vendor publishes plenty of car state but nothing about navigation (`WitsActions` has a
 * `NAVI` *source id* and no guidance channel). An accessibility service could scrape the map,
 * and is deliberately excluded by the permission policy in the manifest.
 *
 * That leaves the ongoing notification the navigating app already posts, which we can read
 * because the notification listener is enabled for media anyway.
 *
 * ## Scope, deliberately narrow
 *
 * [WitsNotificationListenerService] previously read nothing at all. It now reads notifications
 * **from an allowlist of navigation packages, and only while they are ongoing** — see
 * [isNavigation]. Nothing else is inspected, nothing is stored, and nothing reaches the event
 * log or a Signal Explorer session: the text is a street name and a destination, which is
 * location data in all but name. It lives in memory, is rendered, and is replaced by the next
 * one. See docs/security.md §3.9.
 *
 * ## Extraction is firmware- and app-version-specific
 *
 * Which extra carries the manoeuvre varies between navigation apps and between versions of the
 * same one, and some post a fully custom layout with no usable text at all. The parsing below
 * is a best effort with the standard extras, and [lastRawExtras] exposes what actually arrived
 * so the Debug screen can show it and the mapping can be corrected against a real device
 * rather than guessed at.
 */
class NavigationRepository {

    fun interface Listener {
        fun onNavigation(snapshot: NavigationSnapshot)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var snapshot: NavigationSnapshot = NavigationSnapshot()
        private set

    /**
     * Field names and values from the most recent navigation notification, for the Debug
     * screen only. Never logged or exported — see the class doc.
     */
    @Volatile
    var lastRawExtras: Map<String, String> = emptyMap()
        private set

    fun addListener(l: Listener) {
        listeners += l
        l.onNavigation(snapshot)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /** Called by the listener service for every posted notification. */
    fun onPosted(sbn: StatusBarNotification) {
        if (!isNavigation(sbn)) return
        val extras = sbn.notification?.extras ?: return
        lastRawExtras = describe(extras)
        publish(parse(sbn.packageName, extras))
    }

    /** Called by the listener service when a notification goes away. */
    fun onRemoved(sbn: StatusBarNotification) {
        if (!isNavigation(sbn)) return
        // Only clear if the app that ended is the one we were showing, so a stale removal from
        // a different navigator cannot blank a live instruction.
        if (sbn.packageName == snapshot.packageName) {
            lastRawExtras = emptyMap()
            publish(NavigationSnapshot())
        }
    }

    /** Drops everything; used when the listener disconnects. */
    fun clear() {
        lastRawExtras = emptyMap()
        publish(NavigationSnapshot())
    }

    private fun publish(next: NavigationSnapshot) {
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { runCatching { it.onNavigation(next) } }
    }

    companion object {

        /**
         * Packages whose notifications we are willing to read.
         *
         * An allowlist rather than a category test alone: `CATEGORY_NAVIGATION` is
         * self-declared, so any app could set it and have its notification text read by us.
         * Both conditions must hold.
         */
        val NAVIGATION_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.waze",
            "ru.yandex.yandexnavi",
            "com.sygic.aura",
        )

        /**
         * True for an ongoing navigation notification from an allowlisted app.
         *
         * `isOngoing` matters: Maps posts other notifications (traffic, timeline, offers) that
         * are none of our business, and only the guidance one is ongoing.
         */
        fun isNavigation(sbn: StatusBarNotification): Boolean {
            if (sbn.packageName !in NAVIGATION_PACKAGES) return false
            val n = sbn.notification ?: return false
            if (!sbn.isOngoing) return false
            // Prefer the declared category, but do not require it: not every navigator sets it.
            val category = n.category
            return category == null || category == Notification.CATEGORY_NAVIGATION ||
                category == Notification.CATEGORY_SERVICE ||
                category == Notification.CATEGORY_TRANSPORT
        }

        /**
         * Best-effort mapping of the standard extras onto the panel's three fields.
         *
         * Google Maps writes the distance into the title and the manoeuvre into the text
         * ("300 m" / "Turn right onto …"), with the ETA in `sub_text`. Others differ, which is
         * why the raw extras are kept for the Debug screen. Blank strings are treated as
         * absent so the panel does not render an empty line.
         */
        fun parse(packageName: String, extras: Bundle): NavigationSnapshot {
            fun str(key: String): String? =
                runCatching { extras.getCharSequence(key)?.toString() }.getOrNull()
                    ?.trim()?.takeIf { it.isNotEmpty() }

            val title = str(Notification.EXTRA_TITLE)
            val text = str(Notification.EXTRA_TEXT)
            val sub = str(Notification.EXTRA_SUB_TEXT)

            // The title is the distance when it reads like one; otherwise it is the manoeuvre
            // and the text is a detail line. Keeps a navigator that inverts the two readable.
            val titleIsDistance = title != null && DISTANCE.matches(title)
            return NavigationSnapshot(
                available = true,
                packageName = packageName,
                instruction = if (titleIsDistance) text else title ?: text,
                distance = if (titleIsDistance) title else null,
                eta = sub,
            )
        }

        /** "300 m", "1.2 km", "0.5 mi", "250 ft" — a leading number with a unit. */
        private val DISTANCE = Regex("""^\d+([.,]\d+)?\s*(m|km|mi|ft|yd)\.?$""", RegexOption.IGNORE_CASE)

        /** Extra names worth showing on the Debug screen when the mapping needs correcting. */
        private val DESCRIBED_KEYS = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_INFO_TEXT,
        )

        private fun describe(extras: Bundle): Map<String, String> =
            DESCRIBED_KEYS.mapNotNull { key ->
                val v = runCatching { extras.getCharSequence(key)?.toString() }.getOrNull()
                if (v.isNullOrBlank()) null else key.substringAfterLast('.') to v
            }.toMap()
    }
}
