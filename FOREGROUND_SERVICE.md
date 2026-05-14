# Foreground Service for Wear Engine

## What & Why

Wear Engine requires the phone app process to be at importance ≤ 125 (`FG_SERVICE`).  
When the user leaves the app, the process drops to cached (~400) and Wear Engine stops working.  
A **foreground service** pins the process at 125 — even with the screen off or the app in the background.

The trade-off: the OS requires a **non-dismissable notification** to be shown while the service runs.

---

## Implementation

### 1. Manifest

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".service.WatchLinkService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

`dataSync` is used because it has no extra runtime preconditions (no `BLUETOOTH_CONNECT` required).  
Swap to `connectedDevice` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` if Bluetooth permissions are ever added.

### 2. Application class

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLifecycleTracker.install()   // tracks isAppInForeground for the UI
    }
}
```

Register in the manifest with `android:name=".App"`.

### 3. Service — `WatchLinkService`

Two coroutine loops run inside a `SupervisorJob` scope:

- **Heartbeat** (1 s): increments a tick counter and records process importance into `HeartbeatLog`.
- **Watch sender** (5 s): if no device is bound yet, calls `WatchMessenger.tryAutoBind()` first, then sends a message to the watch via `WatchMessenger.send()` — the exact same `P2pManager.sendMessage` path the first screen uses.

Key points:
- Use `startForegroundService()` (not `startService()`) on API 26+.
- Call `startForeground()` **within 5 seconds** — the service handles `ACTION_STOP` before calling it so a stop intent is always safe.
- `START_STICKY` — OS recreates the service after memory pressure.
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

`WatchMessenger.tryAutoBind()` calls back into `MainViewModel.autoBindConnectedDeviceForService()`, which fetches bonded devices and picks the first where `device.isConnected == true`, then runs the normal `selectDevice()` path (registers the P2p receiver, binds `WatchMessenger`). This is triggered:

1. Immediately when the user enables the "Use from watch" toggle.
2. Every 5 s by the watch-sender loop if still no device is bound.

### 6. Notification permission (Android 13+)

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
}
```

The UI requests this before starting the service; if denied, the sticky notification is silently hidden and the service will be killed by the OS after ~10 s on Android 13+.

---

## Process importance reference

| Value | Meaning |
|---|---|
| 100 — `FOREGROUND` | Activity visible on screen |
| **125 — `FG_SERVICE`** | **Background + foreground service running — Wear Engine works ✓** |
| 300 — `SERVICE` | Background service only — Wear Engine may fail |
| 400 — `CACHED` | Normal backgrounded app — Wear Engine fails |
