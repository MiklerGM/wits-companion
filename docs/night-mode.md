# Day / night mode

The user's problem: on this BMW the headlights are effectively always on, so the head
unit sits in night mode during the day, while the factory KOMBI still reacts to real
ambient light.

**Cause:** Android's day/night here is driven by a **boolean illumination line**, not by
a light sensor or an analog ambient value.

**Fix:** the firmware already has a master override — `wits_night_mode` — writable with
plain `WRITE_SETTINGS`. No root, no firmware change.

---

## 1. The illumination signal

```java
// McuManager.java:3402-3416   [CODE]
public void updateIll(int value) {
    if (this.mIll != value) {
        this.mIll = (byte) value;              // byte, 0/1 only
        updateDeepDarkMode();
        UtilExport.setProp(UtilExport.PROP_CAN_ILL, this.mIll);   // sysprop "wits.ill"
        UtilExport.sendIllStatus(this.mContext, this.mIll);        // com.can.ACTION_ILL_INFO
        ...
        ZlinkMessage.sendIllStatus(this.mContext, value);
    }
}
```

- `mIll` is a **`byte` holding 0 or 1** — a pure boolean. `[CODE]`
- There is **no** BMW ambient-light value, **no** KOMBI brightness, **no** terminal
  58g/58d analog level parsed anywhere in CenterService. `[NOTFOUND]`
- Published as sysprop `wits.ill` and broadcast `com.can.ACTION_ILL_INFO` (extra
  `status`). `[CODE]` `UtilExport.java:621-626`

---

## 2. The consumer: SystemUI

```java
// PhoneStatusBarView.java:616-634   [CODE]
static void setThemeByIll(PhoneStatusBarView v) {
    String s = SystemProperties.get("wits.ill");
    if (TextUtils.isEmpty(s)) return;
    int i = Integer.parseInt(s);
    if (i == 0) {
        v.mUiModeManager.setNightMode(1);                 // UiModeManager.MODE_NIGHT_NO
        setAmapAutoDayNightMode(1, v.mContext);
        Settings.System.putInt(cr, "wits_skin", 1);
    } else if (i == 1) {
        v.mUiModeManager.setNightMode(2);                 // MODE_NIGHT_YES
        setAmapAutoDayNightMode(2, v.mContext);
        Settings.System.putInt(cr, "wits_skin", 0);
    }
}
```

The illumination path only runs when the master key allows it:

```java
// PhoneStatusBarView.java:672-680   [CODE]
if (action.equals("com.can.ACTION_ILL_INFO")) {
    if ("witstek8".equals(Settings.System.getString(cr, "UiSettings"))
        && Settings.System.getInt(cr, "wits_night_mode", -1) == 0) {
        setThemeByIll(...);
    }
}
```

---

## 3. The master key: `wits_night_mode`

`Settings.System` key, observed by SystemUI:

```java
// PhoneStatusBarView.java:1245-1270   [CODE]
cr.registerContentObserver(Settings.System.getUriFor("wits_night_mode"), false,
    new ContentObserver(new Handler()) {
        public void onChange(boolean z, Uri uri) {
            int i2 = Settings.System.getInt(cr, "wits_night_mode", -1);
            if (i2 == 0)      setThemeByIll(...);                       // follow headlights
            else if (i2 == 1) WitsClock.handleThemeChangeByTime(ctx,false); // by schedule
            else if (i2 == 2) { mUiModeManager.setNightMode(2); ... }   // force NIGHT
            else if (i2 == 3) { mUiModeManager.setNightMode(1); ... }   // force DAY
        }
    });
```

| Value | Behaviour | Companion label |
|---|---|---|
| `0` | Follow illumination (`wits.ill`) — the problematic default | *Follow headlights* |
| `1` | Follow Witstek time schedule (`wits_backlight_*_hour/minute`) | *Follow Witstek schedule* |
| `2` | Force **night** | *Force night* |
| `3` | Force **day** | *Force day* |

`-1` = unset.

> Important: the `wits_night_mode` **observer fires regardless of `UiSettings`**, unlike
> the ILL branch which additionally requires `UiSettings == "witstek8"`. Writing `2`/`3`
> should therefore deterministically force night/day even if the BMW profile uses a
> different `UiSettings` value. `[CODE]` — needs `[RUNTIME]` confirmation.

Constant declarations: `UtilSetting.java:264` `WITS_NIGHT_MODE = "wits_night_mode"`,
`FactoryKey.java:52` `KEY_MODE_NIGHT = "wits_night_mode"` `[CODE]`.

### Related keys (read-only for us)

| Key | Purpose | Evidence |
|---|---|---|
| `wits_backlight_control_mode` | `1` = time-based backlight control | `BacklightControl.java:66-74` `[CODE]` |
| `wits_backlight_start_hour/_minute` | schedule start | `BacklightControl.java:177-182` `[CODE]` |
| `wits_backlight_end_hour/_minute` | schedule end | as above |
| `wits_skin` | launcher/SystemUI skin (0 dark / 1 light) | `BacklightControl.java:113-119` `[CODE]` |
| `wits_deep_dark_mode`, `_color` | extra dimming layer | `GlobalDef.java:272-296` `[CODE]` |
| `UiSettings` | active UI profile id (e.g. `witstek8`) | `PhoneStatusBarView.java:678` `[CODE]` |

---

## 3.1 Runtime reality on this unit — the cause is different

Captured before the OTA `[RUNTIME]`:

| Key | Value |
|---|---|
| `Settings.System["wits_night_mode"]` | **not set** |
| `Settings.System["UiSettings"]` | **not set** |
| `Settings.System["UiName"]` | `BM_EVOID9_701GEN` |
| `dumpsys uimode` | `mNightMode=2 (yes)`, **`mNightModeLocked=true`**, `mComputedNightMode=true` |

Because `wits_night_mode` is unset, **none of the 0/1/2/3 branches above can be what
holds this unit in night mode.** The likely cause is CenterService instead:

```java
// BacklightControl.java:57-61   [CODE]
String uiSettings = Settings.System.getString(cr, "UiSettings");
if (!"witstek8".equals(uiSettings)) {
    ((UiModeManager) ctx.getSystemService(UiModeManager.class)).setNightMode(2);
}
```

`UiSettings` is unset, so the guard passes and CenterService calls `setNightMode(2)`
unconditionally at start — consistent with `mNightModeLocked=true`. `[HYP-strong]`

**Implication for the companion:** writing `wits_night_mode = 3` should still take effect
(the observer fires on any change regardless of `UiSettings`), but it is now a
*counter-measure* against an unconditional force-night, not a mode selector. Verify that
it survives an ACC cycle — CenterService may re-assert night mode on the next start.

## 4. Four different things — do not conflate

| Concept | Owner | Mechanism | Companion touches it? |
|---|---|---|---|
| **Physical panel brightness** | MCU | PWM duty (`SET_PWM_DUTY_RATIO=208`, `screen_pwm`) | **No** |
| **MCU/backlight dimming on ILL** | MCU + CenterService | `sendIllToMcu`, `Backlight_auto_set` | **No** |
| **Android UI night mode** | SystemUI | `UiModeManager.setNightMode(1\|2)` via `wits_night_mode` | **Yes — only this** |
| **Navigation app day/night** | Nav app | `AUTONAVI_STANDARD_BROADCAST_RECV`, `EXTRA_DAY_NIGHT_MODE` | No (side effect of SystemUI) |

The companion writes exactly one key: `wits_night_mode`. It never touches brightness,
PWM, `wits_skin`, or nav broadcasts.

---

## 5. Safe write flow (`WitsNightModeController`)

```kotlin
// 1. Read current value first, and show the raw value to the user.
val raw = Settings.System.getString(cr, "wits_night_mode")   // may be null

// 2. Check permission — do NOT assume.
if (!Settings.System.canWrite(context)) {
    // 3. Send the user to the standard system screen. Never try to self-grant.
    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        .setData(Uri.parse("package:$packageName")))
    return
}

// 4. Write only after an explicit user choice.
Settings.System.putInt(cr, "wits_night_mode", mode)   // mode ∈ 0,1,2,3

// 5. Read back and log old→new.
```

Rules:

- `WRITE_SETTINGS` is an **appop**, not a runtime permission — `canWrite()` is the only
  reliable check.
- Never write on app start; only on explicit user action.
- Always display the current raw value (including "unset") so the user can restore it.
- Log every write to the event log (`category=night_mode`, old and new value).
- Offer a "restore previous value" action.

---

## 6. Limitations

- **Google Maps** decides its own day/night; on Android Auto/automotive it commonly
  follows the system UI mode, but standalone Maps on a tablet-like device may follow its
  own setting. Forcing `wits_night_mode = 3` changes the **system** UI mode; whether
  Maps follows is `[HYP]` until observed.
- **Spotify** follows its own theme; it will not change with `wits_night_mode`. `[HYP]`
- Apps that ignore `uiMode` (many car-vendor apps) will not change at all.
- Forcing day mode does **not** raise panel brightness. If the panel is dimmed by the MCU
  because of the illumination line, that dimming persists — it is a separate mechanism
  (§4). Adjust brightness via the normal head-unit settings instead.
- `wits_night_mode = 1` (schedule) depends on `wits_backlight_*_hour` being sane; the
  companion shows those values read-only so the user can verify them in WitsSettings.

---

## 7. Runtime test results

> **Not executed — no device attached.** `[HYP]`

| # | Test | Expected | Result | Tag |
|---|---|---|---|---|
| N1 | Read `wits_night_mode` at rest | some value or unset | — | |
| N2 | Read `UiSettings` | e.g. `witstek8` or BMW-specific | — | |
| N3 | Headlights ON, `wits_night_mode=0` | UI goes dark | — | |
| N4 | Set `wits_night_mode=3` with headlights ON | UI turns light immediately | — | |
| N5 | Confirm `UiModeManager` actually changed | `dumpsys uimode` | — | |
| N6 | Does Maps follow? | ? | — | |
| N7 | Does panel brightness change? | expected: **no** | — | |
| N8 | Set back to `0` | returns to headlight-following | — | |
