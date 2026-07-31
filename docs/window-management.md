# Window management — `wits.intent.action.CHANGE_WINDOW`

The single most important vendor hook for this project. It is a **patch in
`system_server`**, not a launcher feature.

Source of truth:
`analysis/jadx/services/sources/com/android/server/wm/ActivityTaskManagerService.java`
(from `system/system/framework/services.jar`).

---

## 1. The hook

### Registration — unprotected

```java
// ActivityTaskManagerService.java:508-514   [CODE]
IntentFilter intentFilter = new IntentFilter();
intentFilter.addAction("wits.intent.action.CHANGE_WINDOW");
intentFilter.addAction("wits.intent.action.MOVE_PIP_WINDOW");
intentFilter.addAction("wits.intent.action.MOVE_PIP_WINDOW_BACK");
this.mContext.registerReceiver(this.mDynamicReceiver, intentFilter);
```

`registerReceiver` is called **without a `broadcastPermission` argument**, inside the
`system_server` context, during `onSystemReady()`. Any app on the device can therefore
send these broadcasts. `[CODE]`

### Dispatch

```java
// ActivityTaskManagerService.java:401-408   [CODE]
if (intent.getAction().equals("wits.intent.action.CHANGE_WINDOW")) {
    String stringExtra = intent.getStringExtra("packageName");
    if (stringExtra == null || stringExtra.equals("")) return;
    Display display = mRootWindowContainer.getDefaultDisplay().getDisplay();
    startActivityByWindowMode(
        stringExtra,
        intent.getIntExtra("windowMode", 1),
        intent.getIntExtra("left",   0),
        intent.getIntExtra("top",    0),
        intent.getIntExtra("right",  display.getWidth()),
        intent.getIntExtra("bottom", display.getHeight()));
}
```

### Implementation

```java
// ActivityTaskManagerService.java:480-494   [CODE]
public void startActivityByWindowMode(String pkg, int mode, int l, int t, int r, int b) {
    ActivityOptions o = ActivityOptions.makeBasic();
    o.setLaunchWindowingMode(mode);
    o.setLaunchBounds(new Rect(l, t, r, b));
    Bundle bundle = o.toBundle();
    int freeformTaskId = getFreeformTaskId(pkg);
    if (freeformTaskId != 0) {
        startActivityFromRecents(freeformTaskId, bundle);   // reuse existing task
        return;
    }
    PackageManager pm = mContext.getPackageManager();
    if (pm.getLaunchIntentForPackage(pkg) == null) return;  // silent no-op
    mContext.startActivity(pm.getLaunchIntentForPackage(pkg), bundle);
}
```

---

## 2. Extras contract

| Extra | Type | Required | Default if absent | Notes |
|---|---|---|---|---|
| `packageName` | `String` | **yes** | — | Empty/null ⇒ silently ignored `[CODE]` |
| `windowMode` | `Int` | no | `1` | `ActivityOptions.setLaunchWindowingMode` value |
| `left` | `Int` | no | `0` | pixels, absolute display coords |
| `top` | `Int` | no | `0` | pixels |
| `right` | `Int` | no | `display.getWidth()` | pixels |
| `bottom` | `Int` | no | `display.getHeight()` | pixels |

There is **no** extra for user id, display id, activity name, or flags. `[CODE]`

---

## 3. Window modes

Values are AOSP `WindowConfiguration.WINDOWING_MODE_*` constants, passed straight
through to `setLaunchWindowingMode`. `[CODE]`

| Value | AOSP constant | Expected effect | Status |
|---|---|---|---|
| `0` | `WINDOWING_MODE_UNDEFINED` | inherit | `[HYP]` |
| `1` | `WINDOWING_MODE_FULLSCREEN` | full screen; bounds largely ignored | `[HYP]` — used by the stock launcher path |
| `2` | `WINDOWING_MODE_PINNED` | system PiP | `[HYP]` not used by us |
| `3` | `WINDOWING_MODE_SPLIT_SCREEN_PRIMARY` | legacy docked primary | `[HYP]` needs runtime test |
| `4` | `WINDOWING_MODE_SPLIT_SCREEN_SECONDARY` | legacy docked secondary | `[HYP]` needs runtime test |
| `5` | `WINDOWING_MODE_FREEFORM` | free window at `bounds` | **primary mode we target** `[HYP]` |

> On Android 13, modes 3/4 are deprecated in AOSP; WMShell drives split via
> `SplitScreenController` instead. They may behave unexpectedly here. Test before use.
> `[HYP]`

### Is freeform actually enabled? — **NO** (verified on the device)

`config_freeformWindowManagement` is **`false`** in `framework-res.apk` `[CONF]`, and no
RRO overrides it `[CONF]`. The shipped `WindowManagerService` constructor does try to
force-enable it at every boot:

```java
// WindowManagerService.java:985-986, inside the ctor that starts at :744   [CODE]
Settings.Global.putInt(cr, "force_resizable_activities", 1);
Settings.Global.putInt(cr, "enable_freeform_support", 1);
```

**But the live device says otherwise:**

| Setting | Live value on v2.6.2 | Tag |
|---|---|---|
| `Settings.Global enable_freeform_support` | **`0`** | `[RUNTIME]` |
| `Settings.Global force_resizable_activities` | **`0`** | `[RUNTIME]` |
| feature `android.software.freeform_window_management` | **absent** | `[RUNTIME]` |
| feature `android.software.picture_in_picture` | present | `[RUNTIME]` |

The reason is AOSP's own Settings app: when **Developer options are disabled**, its
controllers reset both globals to `0`.

```java
// Settings.apk -> development/FreeformWindowsPreferenceController.java   [CODE]
protected void onDeveloperOptionsSwitchDisabled() {
    Settings.Global.putInt(cr, "enable_freeform_support", 0);
}
```

`ResizableActivityPreferenceController` does the same for `force_resizable_activities`
`[CODE]`. So the WMS write at boot is undone, and freeform stays off.

> **The OTA does not change this.** `services.jar` and `framework.jar` are **byte-identical**
> between v2.6.2 and v2.6.3 (`8dc44544…`, `9aaf0861…`) `[RUNTIME]`/`[CONF]`. Only
> `SystemUI.apk` and `WitsLauncher.apk` differ. See `research/runtime-findings.md` §1.

**To actually get freeform:** enable Developer options, switch on *Enable freeform
windows*, reboot. That is a user setting, fully reversible, and not a firmware change.
Until then, `windowMode=5` requests are expected to be ignored and only `windowMode=1`
(fullscreen) behaviour is available. `[HYP]` — confirm after enabling.

## 4. Hard limits (all `[CODE]`)

### 4.1 One window per package

```java
// ActivityTaskManagerService.java:466-478
public final int getFreeformTaskId(String pkg) {
    for (RunningTaskInfo t : am.getRunningTasks(100))
        if (t.topActivity.getPackageName().equals(pkg)) return t.id;
    return 0;
}
```

The **first** running task of that package is reused. You cannot place two windows of
the same package (two Maps, two Spotify). The companion validates this and rejects such
presets (`LayoutValidator`).

### 4.2 MAIN launch intent only

`pm.getLaunchIntentForPackage(pkg)` is used for a cold start. Consequences:

- A package **without a launcher activity is silently ignored**.
- You **cannot pass a deep link** through `CHANGE_WINDOW`.

**Deep-link workaround:** start the deep link yourself first (normal
`startActivity`), then send `CHANGE_WINDOW` for the same package — the task now exists,
so `getFreeformTaskId` finds it and only re-positions it. Implemented as
`LayoutWindow.launchIntentUri`.

### 4.3 Fire-and-forget

`sendBroadcast` returns nothing; the hook returns `void` and logs failures only to
logcat (`Log.d("ActivityTaskManager", …)`). There is **no callback, no result code, no
error**. Success must be inferred out-of-band (e.g. `ActivityManager.getRunningTasks`,
sysprop `wits.top.package`).

### 4.4 No persistence

Nothing stores a *set* of windows. The stock firmware only remembers a single
`default_pip_app` / `default_taskview_app` in `Settings.System` `[CODE]`. Multi-window
persistence is entirely the companion's job.

### 4.5 Resource ceiling

Every tile is a live app. Practical limits are RAM/CPU/thermal, not a code constant —
there is no max-window check in the hook. `[CODE]`

---

## 5. `MOVE_PIP_WINDOW` — not our layout API

```java
// ActivityTaskManagerService.java:449-463   [CODE]
public final void movePipActivity(float x, float y) {
    String pkg = Settings.System.getString(cr, "default_pip_app");
    if (pkg == null || mLastResumedActivity == null
        || !mLastResumedActivity.packageName.equals(pkg)) return;
    Task task = mLastResumedActivity.getTask();
    transaction.setPosition(task.getSurfaceControl(), x, y);
    transaction.apply();
}
```

It only moves the **single** `default_pip_app` task, and only if it is the last resumed
activity. It manipulates the `SurfaceControl` directly — this is how the stock launcher
shows its "floating map", which is why that works even where freeform is off. `[CODE]`

**Decision:** the companion does **not** use `MOVE_PIP_WINDOW` as a layout primitive. It
re-sends `CHANGE_WINDOW` with full bounds instead — idempotent and independent of
`default_pip_app`.

---

## 6. Application order, focus and z-order

`CHANGE_WINDOW` ends in `startActivity` / `startActivityFromRecents`, so **the last
window applied becomes the topmost / focused one**. `[HYP]` (follows from AOSP
semantics; needs `[RUNTIME]` confirmation).

Companion strategy (`LayoutEngine`):

1. Resolve real display bounds and insets.
2. Sort windows by `focusOrder` **ascending**.
3. For each window: if `launchIntentUri` is set and the task does not exist yet, fire the
   deep link first, wait a short settle delay.
4. Send `CHANGE_WINDOW` for each window in that order, with a small inter-window delay.
5. The window with the **highest** `focusOrder` is applied **last** ⇒ gets focus.

## 6.1 Generation token — stale sends must not fire

Every delayed broadcast belongs to a generation. `apply()` and `cancelPending()` bump it,
and each callback re-checks two things **at fire time**, not at request time:

1. its generation is still current — otherwise a superseded layout is silently dropped;
2. `ReverseGuard` still allows the action — otherwise the whole remaining sequence is
   abandoned.

Without this, a retry queued 1.3 s earlier still fires after the driver has engaged
reverse, switched to the OEM screen, or chosen a different preset. Cancelling the handler
queue alone is insufficient, because a callback can already be dispatched when the state
changes.

Cancellation is wired to: a new `apply()`, reverse becoming active
(`LayoutEngine.onCarState`), the source ceasing to be Android
(`LayoutRecoveryCoordinator`), and any source switch
(`WitsSourceController.onBeforeSwitch`, invoked *before* the broadcast leaves).

## 7. Restore strategy

Idempotent re-application with bounded retries — never an infinite loop.

```
apply()                     t = 0 ms
retry #1 (if needed)        t ≈ 400–600 ms
retry #2 (optional, final)  t ≈ 1200–1500 ms
```

Triggers (each individually gated in settings):

| Trigger | Default | Guarded by |
|---|---|---|
| Manual "Restore layout" button | always on | `ReverseGuard` |
| `MainActivity.onResume` | on | `ReverseGuard`, debounce |
| ACC ON | **off** (opt-in) | `ReverseGuard`, `SourceGuard` |
| Android source confirmed | **off** (opt-in) | `ReverseGuard` |
| Reverse ended | **off** (opt-in) | debounce |
| `BOOT_COMPLETED` | **off** (opt-in) | delay + `ReverseGuard` |

Never:

- re-layout while reverse is active,
- switch OEM→Android merely because the companion resumed,
- fight a user who deliberately selected the OEM screen.

---

## 8. Runtime test results

Executed 2026-07-31 on the vehicle, firmware **`WITSTEK-T-M701_OS_EN_v2.6.3_20260513`**
(after the OTA), Developer options enabled, `enable_freeform_support=1`.

Vehicle state during the test: `wits.backcar=0` (reverse not engaged), `wits.acc=1`,
`wits.source=7`, screen awake.

| # | Test | Expected | Result | Tag |
|---|---|---|---|---|
| W1 | `enable_freeform_support` / `force_resizable_activities` | both `1` | **both `1`** | `[RUNTIME]` |
| W2 | `pm list features \| grep picture_in_picture` | present | **present**; `freeform_window_management` **absent** (the global suffices — `ATMS:551`) | `[RUNTIME]` |
| W3 | `CHANGE_WINDOW` Maps, mode 5, left 65 % | Maps occupies left 65 % | **PASS** — `Rect(0, 99 – 1560, 900)`, `mWindowingMode=freeform` | `[RUNTIME]` |
| W4 | `CHANGE_WINDOW` Chrome, mode 5, right 35 % | both visible simultaneously | **PASS** — `Rect(1560, 99 – 2400, 900)`, both `visible=true` | `[RUNTIME]` |
| W5 | Touch both windows | both accept input | **PASS** (user-observed) | `[RUNTIME]` |
| W6 | Left keeps rendering while right focused | live | **PASS** (user-observed: both live) | `[RUNTIME]` |
| W8 | Re-apply the same preset twice | idempotent | **PASS** — identical bounds, same task ids `#22`/`#23`, no duplicates | `[RUNTIME]` |
| W11 | Same package twice | rejected by validator | **PASS** (validator only; not exercised on device) | `[CODE]` |
| W14 | Layout survives minimise → restore | ? | **PASS** — user reports apps reopen in the same places from Home/dashboard | `[RUNTIME]` |

Tested with **Chrome** (`com.android.chrome`) in place of Spotify, which was not
installed at the time. Ratio 65/35 confirmed visually.

### The top-inset pitfall (found and fixed)

The hook passes bounds straight to `setLaunchBounds`. Requesting `y = 0..900` produced
`Rect(0, 99 – 1560, 999)`: the system raised `top` to below the status bar (**99 px** on
this unit) but **preserved the requested height**, so the window hung 99 px off the
bottom of the screen. The user saw a slightly cropped login form.

Requesting the already-offset rect lands exactly right, and the system does **not**
shift a second time. `[RUNTIME]`

```
requested Rect(0,  0, 1560, 900)  ->  actual Rect(0, 99, 1560, 999)   # 99 px off-screen
requested Rect(0, 99, 1560, 900)  ->  actual Rect(0, 99, 1560, 900)   # correct
```

`tools/test-layout.sh` now auto-detects the inset from an existing freeform task and
reserves it (`usable 2400x801 at y=99`); `--inset-top` overrides. The companion's
`WitsWindowController.usableArea()` already computed this correctly from
`WindowMetrics` insets, so `LayoutEngine` is unaffected.

### Open defect: cold start does not land in freeform

**Symptom** `[RUNTIME]` (2026-07-31, from the companion app): applying
*Maps 65 / Spotify 35* opened Maps, then Maps closed and Spotify opened. The two tiles
replaced each other instead of tiling.

**Contrast:** the same two-window layout applied *from the shell* minutes earlier with
Maps + Chrome worked perfectly and was idempotent (W3/W4/W8 above).

**First hypothesis was wrong.** Cold start was ruled out by the user: Apply was pressed
several times, so by the second press both tasks already existed and the warm branch
must have been taken — yet the behaviour repeated. `[RUNTIME]`

**Actual cause — a retry storm in `LayoutEngine`** `[CODE]`, now fixed. The initial pass
staggered its windows by 350 ms, but each *retry* pass fired every window back to back
with no gap:

```
app:     t=0 Maps | t=350 Spotify | t=500 Maps,Spotify | t=1300 Maps,Spotify
script:  t=0 Maps | t=350 Spotify | (nothing)                    <- worked
```

Every `CHANGE_WINDOW` ends in `startActivityFromRecents`, which **pulls that task to the
front**. Firing them back to back does not reinforce a layout, it thrashes the stack and
leaves whichever package went last on top — exactly the reported "Maps opens, Maps
closes, Spotify opens".

Fix: retry passes now stagger identically to the initial pass and start only after it
finishes (`0, 350 | 950, 1300`), and the default drops from 2 retries to 1.
`LayoutEngine.scheduleFor()` exposes the schedule and `LayoutScheduleTest` asserts that
no two broadcasts ever share an instant.

**Superseded hypothesis** (kept for the record) — the cold-start path:

```java
// ActivityTaskManagerService.java:480-494   [CODE]
int freeformTaskId = getFreeformTaskId(pkg);
if (freeformTaskId != 0) {
    startActivityFromRecents(freeformTaskId, bundle);   // WARM: reposition an existing task
    return;
}
mContext.startActivity(pm.getLaunchIntentForPackage(pkg), bundle);   // COLD: fresh launch
```

In the successful shell test **both** Maps and Chrome were already running, so both took
the warm `startActivityFromRecents` branch. Spotify had just been installed and had never
been started, so it took the cold `startActivity` branch — where the launch bounds appear
not to be honoured, and the activity comes up fullscreen over the previous tile.

Competing explanations not yet excluded:

- the companion is itself a fullscreen task on top when it issues the broadcasts, whereas
  the shell test ran with the launcher in front;
- `LayoutEngine`'s bounded retries (500 ms, 1300 ms) re-issue every window and could be
  re-triggering a cold start;
- Spotify's own `launchMode` / task affinity.

**Verification still owed** (next session):

1. Re-install the rebuilt APK and press Apply — expect proper tiling.
2. Reproduce the old behaviour deliberately to confirm the diagnosis:
   `tools/test-layout.sh custom --execute --burst --pkg … --bounds …` fires every window
   with no gap, exactly as the old retry pass did. If that reproduces the symptom from
   the shell, the cause is settled and the caller is exonerated.
3. `tools/test-layout.sh … --retries 1` reproduces the *fixed* staggered pattern.

### Still untested

W7 (audio keeps playing while the other tile has focus — needs a player),
W9/W16 (window modes 3/4), W10 (third window), W12 (package without launcher intent),
W13 (deep link + reposition), W15 (layout after an OEM → Android round trip).
