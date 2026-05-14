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
    android:name=".WatchLinkService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

> **For real Wear Engine usage** swap `dataSync` → `connectedDevice` and the matching permission → `FOREGROUND_SERVICE_CONNECTED_DEVICE`. This also requires the app to hold `BLUETOOTH_CONNECT` (or similar) at the moment `startForeground()` is called on API 34+.

### 2. Service

```kotlin
class WatchLinkService : Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC   // must match manifest
        )
        // start your Wear Engine listener here
        return START_STICKY
    }

    companion object {
        fun start(context: Context) = context.startForegroundService(
            Intent(context, WatchLinkService::class.java)
        )
        fun stop(context: Context) = context.stopService(
            Intent(context, WatchLinkService::class.java)
        )
    }
}
```

Key points:
- Use `startForegroundService()` (not `startService()`) on API 26+.
- Call `startForeground()` **within 5 seconds** or Android kills the process.
- `START_STICKY` — OS recreates the service after memory pressure.

### 3. Notification permission (Android 13+)

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
}
```

Request this before starting the service, otherwise the sticky notification is silently hidden.

---

## Process importance reference

| Value | Meaning |
|---|---|
| 100 — `FOREGROUND` | Activity visible on screen |
| **125 — `FG_SERVICE`** | **Background + foreground service running — Wear Engine works ✓** |
| 300 — `SERVICE` | Background service only — Wear Engine may fail |
| 400 — `CACHED` | Normal backgrounded app — Wear Engine fails |
