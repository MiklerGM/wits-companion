package io.github.miklergm.witscompanion.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.miklergm.witscompanion.app.WitsCompanionApp
import io.github.miklergm.witscompanion.nav.NavigationRepository
import android.util.Log
import android.view.KeyEvent
import io.github.miklergm.witscompanion.wits.WitsPackages
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Notification listener.
 *
 * Its original and still primary job is to exist: enabling it is what unlocks
 * [MediaSessionManager.getActiveSessions]. For a long time it did nothing else, and its doc
 * said so.
 *
 * It now also forwards **navigation** notifications to [NavigationRepository], so the Cockpit
 * can show the next manoeuvre beside the media controls. That is the only way to obtain it on
 * this platform — see that class for why, and for how narrowly it is scoped. Every other
 * notification is ignored here rather than filtered later: nothing else is passed on, so
 * nothing else can be stored or leaked by a mistake further downstream.
 *
 * See docs/media-session.md §2 and docs/security.md §3.9.
 */
class WitsNotificationListenerService : NotificationListenerService() {

    private val navigation: NavigationRepository?
        get() = (application as? WitsCompanionApp)?.navigationRepository

    /**
     * Picks up navigation that was already running when we connected.
     *
     * [onNotificationPosted] only fires for notifications posted *after* the bind, so without
     * this the manoeuvre row stays empty whenever a route is already under way — after an app
     * restart, a reinstall, or any time the system rebinds the listener. Maps would eventually
     * repost on the next distance change and fill it in, but "eventually" on a long straight
     * stretch is a blank row for minutes.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            activeNotifications?.forEach { navigation?.onPosted(it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        runCatching { navigation?.onPosted(n) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        runCatching { navigation?.onRemoved(n) }
    }

    override fun onListenerDisconnected() {
        // The instruction on screen would otherwise outlive our ability to update it.
        runCatching { navigation?.clear() }
        super.onListenerDisconnected()
    }
}

/** What the media panel renders. Never fabricates a "0:00" placeholder track. */
data class MediaSnapshot(
    val available: Boolean = false,
    val permissionGranted: Boolean = false,
    val packageName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    /** Playback position at [positionUpdatedElapsedMs]; extrapolate while playing. */
    val positionMs: Long = 0L,
    val positionUpdatedElapsedMs: Long = 0L,
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    /**
     * Extra actions the player advertises beyond the standard transport — Spotify's
     * "Add to Liked Songs" and similar.
     *
     * Read but not yet acted on: which action a given player exposes, and under what id, is
     * not something to guess at. The Debug screen lists them so the answer comes from a real
     * session rather than an assumption.
     */
    val customActions: List<MediaCustomAction> = emptyList(),
)

/**
 * One [PlaybackState.CustomAction] the current player offers.
 *
 * @param action the id to send back with [MediaSessionRepository.sendCustomAction]
 * @param name   the player's own label — user-facing text, so localised and not an identifier
 */
data class MediaCustomAction(val action: String, val name: String)

/**
 * Wraps [MediaSessionManager] and exposes the currently interesting session.
 *
 * Uses only public Android APIs — no Spotify SDK, no Web API, no credentials,
 * and the app has no INTERNET permission.
 */
class MediaSessionRepository(
    private val appContext: Context,
    private val preferredPackage: String = WitsPackages.SPOTIFY,
) {

    fun interface Listener {
        fun onMedia(snapshot: MediaSnapshot)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val componentName = ComponentName(appContext, WitsNotificationListenerService::class.java)

    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var started = false

    @Volatile
    var snapshot: MediaSnapshot = MediaSnapshot()
        private set

    // ------------------------------------------------------------- permission

    fun isPermissionGranted(): Boolean = runCatching {
        val flat = Settings.Secure.getString(
            appContext.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        flat.split(":").any { entry ->
            ComponentName.unflattenFromString(entry)?.packageName == appContext.packageName
        }
    }.getOrDefault(false)

    fun permissionIntent() = android.content.Intent(
        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    )

    /** True when the app can grant notification access itself (platform build). */
    fun canSelfGrant(): Boolean =
        appContext.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Grants our listener notification access by appending it to
     * `enabled_notification_listeners`. Needs `WRITE_SECURE_SETTINGS` (held on the platform
     * build), which is why this exists at all: the vendor's system menu for notification
     * access is unreachable on this head unit, and the grant is lost on every reinstall.
     *
     * @return true if the setting now contains our component (already-granted counts)
     */
    fun grantSelf(): Boolean {
        if (isPermissionGranted()) return true
        if (!canSelfGrant()) return false
        return runCatching {
            val current = Settings.Secure.getString(
                appContext.contentResolver, "enabled_notification_listeners"
            )?.takeIf { it.isNotBlank() }
            val ours = componentName.flattenToString()
            val merged = when {
                current == null -> ours
                current.split(":").contains(ours) -> current
                else -> "$current:$ours"
            }
            Settings.Secure.putString(
                appContext.contentResolver, "enabled_notification_listeners", merged
            )
            isPermissionGranted()
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------- lifecycle

    /** True once the active-sessions listener is registered, so it is only added once. */
    private var observing = false

    fun start() {
        if (started) return
        started = true
        manager = appContext.getSystemService(MediaSessionManager::class.java)
        ensureObserving()
    }

    /**
     * (Re)registers the active-sessions listener and refreshes the snapshot. Idempotent, and
     * safe to call before permission exists — registration needs the listener to be an
     * enabled notification listener, so it is retried here rather than only once at [start].
     *
     * This is what a media screen calls on open: notification access can be granted *after*
     * the app started (self-grant, or ADB on this head unit), and without a re-check the
     * panel would keep showing "not granted" until the process restarts. `[RUNTIME]`
     * 2026-08-01 — the repository was never started at all, so media stayed dead until this.
     */
    fun ensureObserving() {
        if (manager == null) manager = appContext.getSystemService(MediaSessionManager::class.java)
        if (!observing && isPermissionGranted()) {
            runCatching {
                manager?.addOnActiveSessionsChangedListener(sessionsChanged, componentName)
                observing = true
            }.onFailure { Log.d(TAG, "cannot observe sessions yet: ${it.javaClass.simpleName}") }
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        observing = false
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun addListener(l: Listener) {
        listeners += l
        mainHandler.post { l.onMedia(snapshot) }
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    // ---------------------------------------------------------------- refresh

    fun refresh() {
        if (!isPermissionGranted()) {
            controller?.unregisterCallback(controllerCallback)
            controller = null
            emit(MediaSnapshot(available = false, permissionGranted = false))
            return
        }

        val sessions = runCatching {
            manager?.getActiveSessions(componentName).orEmpty()
        }.getOrElse {
            Log.d(TAG, "getActiveSessions failed: ${it.javaClass.simpleName}")
            emit(MediaSnapshot(available = false, permissionGranted = false))
            return
        }

        val chosen = select(sessions)
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = chosen
            controller?.registerCallback(controllerCallback)
        }
        publish()
    }

    /**
     * Selection policy (docs/media-session.md §3):
     * preferred package → any playing session → any session with metadata.
     */
    private fun select(sessions: List<MediaController>): MediaController? =
        sessions.firstOrNull { it.packageName == preferredPackage }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }

    private fun publish() {
        val c = controller
        if (c == null) {
            emit(MediaSnapshot(available = false, permissionGranted = true))
            return
        }
        val md = c.metadata
        val ps = c.playbackState
        val actions = ps?.actions ?: 0L
        emit(
            MediaSnapshot(
                available = true,
                permissionGranted = true,
                packageName = c.packageName,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                albumArt = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                positionMs = ps?.position ?: 0L,
                // PlaybackState.position is the position as of lastPositionUpdateTime, not as
                // of now — both in the elapsedRealtime timebase. Stamping it with the snapshot
                // time instead made the UI extrapolate from the wrong origin, so progress
                // lagged (or jumped) by however long ago the player last published a state.
                positionUpdatedElapsedMs = ps?.lastPositionUpdateTime?.takeIf { it > 0L }
                    ?: android.os.SystemClock.elapsedRealtime(),
                canPlay = actions and PlaybackState.ACTION_PLAY != 0L,
                canPause = actions and PlaybackState.ACTION_PAUSE != 0L,
                canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                customActions = ps?.customActions.orEmpty().mapNotNull { a ->
                    val id = a.action?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MediaCustomAction(id, a.name?.toString().orEmpty())
                },
            )
        )
    }

    private fun emit(s: MediaSnapshot) {
        snapshot = s
        mainHandler.post { listeners.forEach { runCatching { it.onMedia(s) } } }
    }

    // --------------------------------------------------------------- controls

    fun playPause() {
        val c = controller
        if (c == null) {
            // No live session to command — the player has not run since boot, so there is nothing
            // to control and the button used to be simply dead ("won't start playback until I open
            // Spotify myself"). A media key still reaches the player's media-button receiver and
            // cold-starts its playback service. `[RUNTIME]` 2026-08-17: with 0 sessions, a
            // KEYCODE_MEDIA_PLAY brought Spotify up playing (state=3) **without** opening its UI, so
            // the Cockpit keeps the map on screen.
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    /**
     * Fires one of the player's own custom actions.
     *
     * There is no fallback: unlike play, a custom action has no media-key equivalent, so with
     * no live session there is nothing to send and nothing sensible to invent.
     *
     * @return false when there was no session, or the player rejected it
     */
    fun sendCustomAction(action: String, extras: android.os.Bundle? = null): Boolean {
        val c = controller ?: return false
        return runCatching {
            c.transportControls.sendCustomAction(action, extras)
            true
        }.getOrDefault(false)
    }

    fun next() {
        val c = controller
        if (c == null) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        else c.transportControls.skipToNext()
    }

    fun previous() {
        val c = controller
        if (c == null) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        else c.transportControls.skipToPrevious()
    }

    /**
     * Sends a media key the way a steering-wheel/headset button does, for when no MediaSession
     * exists yet. The framework routes it to the last-known or manifest-registered media button
     * receiver, which wakes the player's playback service. Requires the down/up pair — a lone
     * ACTION_DOWN is ignored by most players.
     */
    private fun dispatchMediaKey(keyCode: Int) {
        val audio = appContext.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            Log.d(TAG, "no session — dispatched media key $keyCode")
        }.onFailure { Log.w(TAG, "dispatchMediaKeyEvent failed: ${it.javaClass.simpleName}") }
    }

    /** Launches the player's own UI. */
    fun openPlayer(): android.content.Intent? {
        val pkg = controller?.packageName ?: preferredPackage
        return appContext.packageManager.getLaunchIntentForPackage(pkg)
    }

    private companion object {
        const val TAG = "WitsMediaRepo"
    }
}
