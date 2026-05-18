# Foreground Service for Wear Engine

## What & Why

Wear Engine requires the phone app process to be at importance ≤ 125 (`FG_SERVICE`).
When the user leaves the app, the process drops to cached (~400) and Wear Engine stops working.
A **foreground service** pins the process at 125 — even with the screen off or the app in the background.

The trade-off: the OS requires a **non-dismissable notification** while the service runs,
and a FG service is **not** automatically exempt from Doze or OEM power managers
(Huawei PowerGenie / HwPFWService etc.) — see [Battery & OEM power management](#battery--oem-power-management).

---

## Implementation

### 1. Manifest

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<service
    android:name=".service.WatchLinkService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice|dataSync" />
```

We declare **both** FG types. At runtime the service decides which to actually
pass to `startForeground()` based on whether `BLUETOOTH_CONNECT` is granted:

- **Granted** → `CONNECTED_DEVICE | DATA_SYNC` (semantically accurate, slightly
  more lenient OEM treatment).
- **Not granted / API < 31** → `DATA_SYNC` only (no runtime prereq — won't throw
  `ForegroundServiceTypeNotAllowedException` on the emulator).

### 2. Application class

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLifecycleTracker.install()       // tracks isAppInForeground for the UI
        installAppLevelAutoBinder()         // survives Activity death
    }
}
```

Register in the manifest with `android:name=".App"`.

### 3. Service — `WatchLinkService`

Two coroutine loops run inside a `SupervisorJob` scope:

- **Heartbeat** (1 s): increments a tick counter and records process importance into `HeartbeatLog`.
- **Watch sender** (5 s): if no device is bound yet, calls `WatchMessenger.tryAutoBind()` first, then sends a message via `WatchMessenger.send()` — same `P2pManager.sendMessage` path as the first screen.

Key points:

- Use `startForegroundService()` (not `startService()`) on API 26+.
- Call `startForeground()` **within 5 seconds** — the service handles `ACTION_STOP` before that call so a stop intent is always safe.
- `START_STICKY` — OS recreates the service after memory pressure.
- Holds a `PARTIAL_WAKE_LOCK` for the lifetime of the service so the 5 s loop fires
  with screen off + unplugged. On Huawei the tag is `"LocationManagerService"`
  (one of HwPFWService's hardcoded whitelisted tags) to dodge PowerGenie kills.
- Notification channel is `IMPORTANCE_DEFAULT` (not `LOW`) with `PRIORITY_HIGH`
  + `VISIBILITY_PUBLIC` so OEMs can't quietly demote our process when the
  screen turns off.
- Both loops are cancelled in `onDestroy()` before the scope is cancelled.

### 4. WatchMessenger (process-wide singleton)

Bridges the UI / ViewModel and the service without passing a `Context`:

```
ViewModel.selectDevice()  ──►  WatchMessenger.bind(p2pManager, device)
                               WatchMessenger.setAutoBinder { ... }
WatchLinkService (5 s)    ──►  WatchMessenger.tryAutoBind()  (if no device)
                               WatchMessenger.send(seq, text)
```

Exposes `StateFlow`s for sent count, failed count, last entry, full entry log, received messages — all collected in the UI with `collectAsStateWithLifecycle()`.

### 5. Auto-bind

`WatchMessenger.tryAutoBind()` calls into an **app-scope** auto-binder installed
in `App.onCreate()` (not the ViewModel), so it survives Activity destruction.
It fetches bonded devices and picks the first where `device.isConnected == true`,
then runs the normal `selectDevice()` path. Triggered:

1. Immediately when the user enables the "Use from watch" toggle.
2. Every 5 s by the watch-sender loop if still no device is bound.

---

## Battery & OEM power management

A foreground service is **not** Doze-exempt. Screen off + unplugged → stock
Android Doze suspends our network access; on Huawei, PowerGenie / App Launch
manager also kills the process after a few minutes even with a wake lock.

`BatteryOptimization` exposes three intents:

| Intent | Purpose |
|---|---|
| `requestIgnoreIntent(context)` | System dialog "Allow X to ignore battery optimization?". **No** `FLAG_ACTIVITY_NEW_TASK` — required by `ActivityResultLauncher`, otherwise OEMs drop it silently. |
| `settingsListIntent()` | Fallback: full battery-optimization list screen. |
| `oemPowerManagerIntent(context)` | Best-effort component intent into Huawei / Xiaomi / Oppo / Vivo / OnePlus app-launch manager. |

The UI surfaces a `BatteryWhitelistCard` (red until whitelisted) and, when
`Build.MANUFACTURER` is Huawei/Honor, an extra `HuaweiInstructionsCard`
explaining the **mandatory** manual steps (the AOSP whitelist alone is not enough):

> Phone Manager → Battery → App launch → this app → turn OFF "Manage
> automatically" → turn ON Auto-launch, Secondary launch, Run in background.

Every button shows a `Toast` describing what actually happened ("Already
whitelisted", "Direct prompt not supported — opening settings list",
"Prompt failed (ActivityNotFoundException)") so silent OEM failures are
visible to the user.

---

## Process importance reference

| Value | Meaning |
|---|---|
| 100 — `FOREGROUND` | Activity visible on screen |
| **125 — `FG_SERVICE`** | **Background + foreground service running — Wear Engine works ✓** |
| 300 — `SERVICE` | Background service only — Wear Engine may fail |
| 400 — `CACHED` | Normal backgrounded app — Wear Engine fails |
