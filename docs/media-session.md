# Media panel — Spotify via Android MediaSession

The companion controls Spotify (and any other player) through **standard Android APIs
only**. No Spotify Web API, no Spotify App Remote SDK, no credentials, no network.

This is the same mechanism Mini AA uses ("requires Notification Access"), and it is
player-agnostic.

---

## 1. Why MediaSession and not the launcher's music card

The stock `WitsLauncher` music card reads track metadata with
`android.media.MediaMetadataRetriever` **from a file path** — it reflects only the
built-in WitsMusic player and can never show Spotify. `[CODE]`
`analysis/jadx/WitsLauncher/sources/com/wits/launcher/launcher/model/MediaImpl.java`
(no `MediaSessionManager`, no `getActiveSessions` anywhere `[NOTFOUND]`).

So the companion implements its own panel.

---

## 2. Permission model

`MediaSessionManager.getActiveSessions(ComponentName)` requires the caller to be either
a notification listener or hold `MEDIA_CONTENT_CONTROL` (a signature permission we cannot
get). The supported path for a normal app is:

1. Declare a `NotificationListenerService` with
   `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`.
2. Ask the user to enable it in
   `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
3. Pass that service's `ComponentName` to `getActiveSessions`.

```kotlin
val nm = getSystemService(MediaSessionManager::class.java)
val listener = ComponentName(this, WitsNotificationListenerService::class.java)
val sessions: List<MediaController> = nm.getActiveSessions(listener)   // throws SecurityException if not granted
```

The service itself does **nothing** with notifications — it exists purely to unlock
`getActiveSessions`. It is `exported="false"` apart from the mandatory system binding
intent filter (the system requires the framework to bind it; the permission
`BIND_NOTIFICATION_LISTENER_SERVICE` ensures only the system can).

### Permission state machine

| State | Detection | UI |
|---|---|---|
| Not granted | `NotificationManagerCompat.getEnabledListenerPackages(ctx)` lacks our package | Onboarding card + "Open settings" button |
| Granted, no session | `getActiveSessions()` empty | "No player running — open Spotify" |
| Granted, session present | non-empty list | Full panel |
| `SecurityException` | thrown by `getActiveSessions` | Treat as not granted, re-show onboarding |

The media panel is **optional**: every other companion feature works without it.

---

## 3. Session selection

Multiple sessions can be active (Spotify + a video app + the OEM BT source). Selection
policy, in order:

1. Prefer the session whose `packageName` is the user's configured preferred player
   (default `com.spotify.music`).
2. Otherwise prefer a session whose `PlaybackState.state == STATE_PLAYING`.
3. Otherwise the first session with non-null metadata.
4. Otherwise none → show "no player".

Track changes with `MediaSessionManager.addOnActiveSessionsChangedListener` plus a
`MediaController.Callback` for the selected controller.

---

## 4. Data surfaced

From `MediaController.getMetadata()` (`MediaMetadata`):

| Panel field | Key |
|---|---|
| Title | `METADATA_KEY_TITLE` |
| Artist | `METADATA_KEY_ARTIST` (fallback `METADATA_KEY_ALBUM_ARTIST`) |
| Album | `METADATA_KEY_ALBUM` |
| Album art | `METADATA_KEY_ALBUM_ART` → `METADATA_KEY_ART` → `..._ART_URI` |
| Duration | `METADATA_KEY_DURATION` |

From `MediaController.getPlaybackState()` (`PlaybackState`):

| Panel field | Source |
|---|---|
| Playing / paused | `state == STATE_PLAYING` |
| Position | `position` + `lastPositionUpdateTime` + `playbackSpeed` |
| Available actions | `actions` bitmask — used to enable/disable buttons |

Album art can be large; scale to the panel size and cache by
`METADATA_KEY_MEDIA_ID` to avoid per-frame decoding.

---

## 5. Controls

Via `MediaController.getTransportControls()`:

| Button | Call | Enable when |
|---|---|---|
| Play | `play()` | `actions and ACTION_PLAY != 0L` |
| Pause | `pause()` | `actions and ACTION_PAUSE != 0L` |
| Play/pause toggle | `play()` / `pause()` by state | either bit set |
| Previous | `skipToPrevious()` | `ACTION_SKIP_TO_PREVIOUS` |
| Next | `skipToNext()` | `ACTION_SKIP_TO_NEXT` |
| Seek | `seekTo(ms)` | `ACTION_SEEK_TO` (post-MVP) |
| Open player | `packageManager.getLaunchIntentForPackage(pkg)` | package installed |

Never synthesise media key events with `dispatchMediaButtonEvent` as a primary path — it
is less reliable and can leak to the wrong app.

---

## 6. If Spotify is not running

`getActiveSessions()` will not contain it. The panel then:

- shows a neutral "not playing" state (never a fake `0:00` track),
- offers **Open Spotify** (`getLaunchIntentForPackage("com.spotify.music")`),
- optionally offers **Open Spotify in the right tile**, which is
  `LayoutEngine.applyPreset()` for the Spotify tile — i.e. it reuses the window path
  rather than doing anything media-specific.

The companion never auto-starts playback.

---

## 7. Availability on this device

`com.spotify.music` version `8.9.48.575` (`versionCode 115347385`) is **preinstalled** in
`system/system/PreInstall/Spotify.apk`, signed by Spotify's own certificate `[CONF]`
(`analysis/out/packages.csv`). Google Maps `11.79.0301` is likewise preinstalled `[CONF]`.

So both target apps exist out of the box; the companion only needs `<queries>` entries to
see them under Android 11+ package visibility.

---

## 8. Privacy

- Track metadata is shown in the UI but **not** written to the event log by default
  (`EventLogger` redacts media titles unless verbose debug logging is explicitly enabled).
- No metadata leaves the device — the app has **no `INTERNET` permission**.

---

## 9. Runtime test results

> **Not executed — no device attached.** `[HYP]`

| # | Test | Expected | Result | Tag |
|---|---|---|---|---|
| M1 | Notification access onboarding appears when not granted | onboarding card | — | |
| M2 | After granting, Spotify session detected | title/artist shown | — | |
| M3 | Play/pause from panel | Spotify responds | — | |
| M4 | Next/previous | track changes | — | |
| M5 | Album art renders | image shown | — | |
| M6 | Spotify killed → panel state | "not playing", no crash | — | |
| M7 | Panel works while Spotify is in the other tile | both update | — | |
| M8 | OEM BT audio session present | selection policy picks the right one | — | |
