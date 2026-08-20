# Security model

Two halves: (1) what this firmware exposes, and (2) what the companion does about it.

---

## 1. Threat surface in the firmware

### 1.1 Unprotected vendor broadcasts

`system_server` registers the window hook with **no broadcast permission**:

```java
// ActivityTaskManagerService.java:508-514   [CODE]
intentFilter.addAction("wits.intent.action.CHANGE_WINDOW");
intentFilter.addAction("wits.intent.action.MOVE_PIP_WINDOW");
intentFilter.addAction("wits.intent.action.MOVE_PIP_WINDOW_BACK");
this.mContext.registerReceiver(this.mDynamicReceiver, intentFilter);
```

CenterService does the same for its whole action set
(`CenterService.java:1998-2019`) `[CODE]`.

**Impact:** any installed app — including a malicious sideload — can move/launch windows,
request a source switch, inject key codes, and reach the MCU. This is a property of the
device, not of the companion. It is the reason the companion is possible at all, and also
the reason to be conservative.

### 1.2 Source-switch abuse

The `caller` "magic" `0xA7000000` (`UtilExport.java:157`, checked at
`CenterService.java:1878`) `[CODE]` is **obfuscation, not authorisation** — trivially
forged. A hostile app could:

- force the OEM screen up while driving,
- ping-pong the source,
- switch away from the reverse camera while reversing. **← safety-relevant**

### 1.3 Key injection

`com.can.ACTION_KEY_CODE` + extra `key_code` → `mMcuManager.doKeyNoBeep(key)`
(`CenterService.java`) `[CODE]`. Synthetic hardware key events into the MCU.

### 1.4 Raw MCU send

Two unprotected paths reach the serial line directly (see `mcu-protocol.md` §4):
`com.can.ACTION_CAN_CENTER_REV` (`data: ByteArray`) and
`com.center.ACTION_COMMON_CMD_REV` (`cmd = 0x300000`). Worst case includes the
`CMD_UPGRADE_*` opcodes — i.e. an MCU firmware write path. `[HYP]` on exploitability,
`[CODE]` on reachability.

### 1.5 Permissive SELinux + userdebug

`boot.img` cmdline contains `androidboot.selinux=permissive` `[CONF]`;
`ro.debuggable=1`, `ro.build.type=userdebug`, `persist.sys.usb.config=adb` `[CONF]`.
SELinux denials will not stop anything, and ADB is enabled by default. Hidden-API
restrictions are also weakened on such builds `[HYP]`.

### 1.6 Exported components

`analysis/out/exported-components.csv` lists 2 221 exported entries. Notably
`com.my.service.CenterService` and `com.my.service.MiscService` are
`exported="true"` with no permission `[CODE]`.

### 1.7 Boot watchdog

`vendor/bin/wits_err_reboot.sh` reboots into a **recovery wipe** if
`system.wits.boot.ok` is not set within 80 s `[CODE]`. A change that delays boot can
therefore destroy user data. Relevant to anything that installs at boot.

---

## 2. What this means for the user

The device is, by construction, **open to any app installed on it**. The practical
mitigation is ordinary hygiene: do not sideload untrusted APKs onto the head unit. The
companion cannot close these holes — it can only avoid widening them.

---

## 3. Companion countermeasures

### 3.1 No dangerous capability, by construction

| Capability | Status |
|---|---|
| Raw MCU frame send | **Not implemented.** No code path constructs a ProtocolWits frame. |
| MCU firmware update opcodes | **Not implemented.** |
| Key-code injection | **Not implemented.** |
| Radio/brightness MCU commands | **Not implemented.** |
| Partition writes / `wits_sudo.sh` / `dd` | **Not implemented.** |
| Root / su / Magisk | **Not used, not required.** |
| Bootloader / AVB / verity changes | **Not implemented.** |

The only outbound vendor calls are: `CHANGE_WINDOW`, `ACTION_REQUEST_SWITCH_SOURCE`
(manual only), and `Settings.System.putInt("wits_night_mode", …)`.

### 3.2 Nothing exported

Every manifest component is `android:exported="false"` except where the platform
mandates otherwise:

| Component | Exported | Why |
|---|---|---|
| `MainActivity` | `true` | needs LAUNCHER category — unavoidable, and it is only a UI entry point |
| `WitsNotificationListenerService` | `false` + `BIND_NOTIFICATION_LISTENER_SERVICE` | only the system may bind |
| `BootReceiver` | `false`, `RECEIVE_BOOT_COMPLETED` | protected system broadcast |
| Everything else | `false` | — |

`WitsBroadcastReceiver` is registered **at runtime** and never declared in the manifest.

**Correction (2026-07-31):** it was originally registered `RECEIVER_NOT_EXPORTED`, which
was a defect. The senders — `com.wits.pms`, `com.wits.misc`, SystemUI — are *other
processes*, and a `NOT_EXPORTED` receiver is never delivered their broadcasts. The
car-state feed was silently dead while the Signal Explorer probe (correctly `EXPORTED`)
did receive events.

It is now `RECEIVER_EXPORTED`, and the isolation is replaced by properties of the
receiver itself:

- only the exact actions in `WitsActions.CAR_STATE_ACTIONS` are acted on, re-checked in
  `onReceive` independently of the IntentFilter;
- it never executes a command, starts a component, or writes state — it is a pure
  observer;
- extras are read defensively, bounded to 512 chars, and unparseable values degrade to
  `INVALID` with the raw text retained rather than being trusted;
- the worst a spoofed broadcast achieves is a wrong number on the dashboard.

Crucially, **safety decisions do not rest on this receiver alone**. `ReverseGuard` also
consults the `wits.backcar` property and the source id, and the two transports are resolved
against each other rather than overwriting one another — the rule is deliberately asymmetric
(`CarStateRepository.keepTrustedPositive`):

- a broadcast may always **raise** a safety alarm (worst case a hostile app blocks *our own*
  automation, which fails safe);
- it may not **clear** an alarm that the polled property — the transport an app cannot write —
  is still asserting and confirmed within the trust window.

Legitimate clearing is not delayed: the next poll is at most one interval away and carries the
same news on the trusted transport. A spoofed "reverse released" broadcast therefore cannot by
itself unblock an automatic action.

Separately, evidence **expires**. `CarState.reverseActiveForControl()` accepts positive evidence
however old, but requires negative evidence to be fresher than `ReverseGuard`'s
`controlMaxAgeMs`; a `reverse=false` that stops being refreshed decays to *unknown*, not to
*safe*, so automatic actions fail closed once telemetry disappears. The display value
(`reverseActive`) keeps showing the last reading, which is correct for a dashboard and wrong
for a control decision — hence the split.

### 3.3 No remote control surface

- No `INTERNET` permission → nothing can be uploaded or remotely triggered.
- No exported `BroadcastReceiver`, `Service` or `ContentProvider` for dangerous actions.
- No deep-link/intent-filter that performs a source switch or a layout change.
  Layout and source actions are reachable **only** from in-app UI.

### 3.4 Reverse guard (safety-critical)

`safety/ReverseGuard.kt` vetoes: layout application, source switching, and any overlay,
whenever reverse is active or reverse state is unknown-and-the-action-is-automatic
("fail closed"). Inputs and rules: `source-switching.md` §5.

### 3.5 Rate limiting

`safety/ActionRateLimiter.kt` — token bucket per action class:

| Action | Limit (default) |
|---|---|
| Source switch | 1 per 5 s, max 3 per minute |
| Layout apply | 1 per 2 s, max 10 per minute |
| Night-mode write | 1 per 2 s |

Prevents both user-driven and bug-driven ping-pong.

### 3.6 Permissions actually requested

| Permission | Why | Optional? |
|---|---|---|
| `RECEIVE_BOOT_COMPLETED` | opt-in layout restore at boot | yes (feature off by default) |
| `WRITE_SETTINGS` | `wits_night_mode` only | yes (appop, user-granted) |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | media panel only | yes |
| `<queries>` for 4 packages | package visibility | — |

**Not requested:** `INTERNET`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`,
`ACCESS_FINE_LOCATION`, Accessibility, `FOREGROUND_SERVICE` (MVP), storage.

### 3.7 Logging and redaction

`logging/EventLogger.kt` writes JSON Lines locally. `LogRedactor` removes or masks:

| Category | Handling |
|---|---|
| VIN-like tokens (17-char alnum) | masked |
| Serial numbers (`ro.serialno`, `ro.boot.serialno`) | dropped |
| MAC / Bluetooth addresses | masked |
| Wi-Fi SSID / PSK / AP credentials | dropped |
| Phone name, contact names | dropped |
| Media track title/artist | dropped unless verbose debug is explicitly enabled |
| IMEI/subscriber ids | dropped |

Redaction is **key-aware and recursive** (`LogRedactor.redactJson`), applied to the whole JSON
tree before anything is written — event log and Signal Explorer session files alike. Matching on
serialized text alone would only catch the MAC/VIN/long-digit *shapes*; an SSID or a track title
is identified by its key, so the walk has to see keys.

Captured broadcast extras get a second rule, because they do not nest their key as a JSON key: a
`CapturedExtra` serializes as `{"name":"ssid","javaType":…,"value":…}`, so when `name` names
something sensitive the sibling `value`, `hex` and `base64` fields are dropped with it.

Session files are additionally capped at `SessionRecorder.MAX_SESSION_BYTES`; a session that hits
the cap stops writing and is marked with a `TRUNCATED` file rather than filling the data
partition (broadcast extras can carry whole byte arrays as hex).

Export is user-initiated via the Storage Access Framework (`ACTION_CREATE_DOCUMENT`), so
the app never needs storage permissions and the user picks the destination.

### 3.8 Uninstall = clean

The companion writes only to its own app storage plus, if the user chose it, the single
`wits_night_mode` setting. Uninstalling restores stock behaviour; the night-mode value
can be reset from the app beforehand (a "restore previous value" action is provided).

---

## 4. Residual risks accepted

| Risk | Mitigation | Residual |
|---|---|---|
| Layout applied at a bad moment | reverse guard, manual-first defaults, debounce | user can still trigger manually while driving |
| Source switch confuses the MCU | rate limit, `recoverFlag=0`, wait for state event | firmware-side bounce may persist `[HYP]` |
| Night-mode write annoys the user | read-back, show raw value, restore action | none significant |
| Another app abuses the same hooks | out of our control | document it (§1) |
| Hidden-API access breaks on update | reflection is wrapped in try/catch with fallbacks | feature degrades, app still runs |
