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
import android.util.Log
import android.view.KeyEvent
import io.github.miklergm.witscompanion.wits.WitsPackages
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Notification listener that exists solely to unlock
 * [MediaSessionManager.getActiveSessions].
 *
 * It reads no notifications and does nothing with them.
 * See docs/media-session.md §2.
 */
class WitsNotificationListenerService : NotificationListenerService()

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
)

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
                positionUpdatedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                canPlay = actions and PlaybackState.ACTION_PLAY != 0L,
                canPause = actions and PlaybackState.ACTION_PAUSE != 0L,
                canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
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
