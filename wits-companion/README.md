# Wits Companion

Companion app for Witstek/XTRONS BMW head units. Uses only vendor APIs that already
exist in the shipped firmware — no root, no platform signature, no partition changes.

Full documentation: [`../docs/`](../docs/). Start with [`../docs/README.md`](../docs/README.md).

## Namespace

`io.github.miklergm.witscompanion` — chosen at project creation; no pre-existing
namespace was present in this workspace.

## Build

```sh
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # 38 unit tests
```

Requires JDK 17 and an Android SDK with platform 35. Set `sdk.dir` in
`local.properties` (already generated) or `ANDROID_HOME`.

Toolchain: Gradle wrapper 8.13 + AGP 8.7.3. The system Gradle 9.6 is **not**
compatible with AGP 8.x — always use `./gradlew`.

## Package layout

```
app/          Application, dependency graph
wits/         Vendor API surface: actions, properties, window/source/night controllers
carstate/     Signal model, property reader, broadcast receiver, repository, simulator
layout/       Presets, validation, engine, persistence, recovery, boot receiver
media/        MediaSession repository + notification listener
safety/       ReverseGuard, SourceGuard, ActionRateLimiter
logging/      JSONL event logger + redaction
ui/           MainActivity and its sections
```

## Safety invariants

- No raw MCU frame is ever constructed or sent.
- Nothing is exported except `MainActivity` (needs LAUNCHER).
- The car-state receiver is registered `RECEIVER_NOT_EXPORTED` at runtime.
- No `INTERNET`, no `SYSTEM_ALERT_WINDOW`, no `QUERY_ALL_PACKAGES`, no Accessibility.
- Layout and source actions are refused while reverse is active or unknown-and-automatic.
- Source switching is always manual.
