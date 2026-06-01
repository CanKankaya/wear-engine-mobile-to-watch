> **Note:** To access all shared projects, get information about environment setup, and view other guides, please visit [Explore-In-HMOS-Wearable Index](https://github.com/Explore-In-HMOS-Wearable/hmos-index).

# [Mobile to Watch] WearEngine Foreground Service

Android demo that keeps a Huawei Wear Engine P2P connection alive in the
background using a foreground service. The phone auto-binds to a connected
watch and periodically exchanges heartbeat messages, surviving Home, Recents
swipe, screen-off and Doze. Focused entirely on the background-reliability
problem — there is no manual device picker or file transfer UI.

# Preview

| ![image_1](./screenshots/preview_01.png) | ![image_2](./screenshots/preview_02.png) | ![image_3](./screenshots/preview_03.png) | ![image_4](./screenshots/preview_04.png) |
|-----------------------------------------|-----------------------------------------|-----------------------------------------|-----------------------------------------|

# Use Cases
- Keep a Wear Engine connection alive while the app is backgrounded
- Automatically discover and bind a paired, connected wearable
- Send periodic heartbeat messages to the watch from a foreground service
- Diagnose process importance, battery optimization and OEM power management
- Permission management for wearable operations

# Technology
## Stack
- Languages: Kotlin
- Frameworks: Jetpack Compose, Wear Engine SDK, Huawei Wear Engine Services
- Tools: Android Studio,Gradle, Huawei AppGallery Connect

## Required Permissions and Configs

```android.permission.FOREGROUND_SERVICE```
```android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE```
```android.permission.FOREGROUND_SERVICE_DATA_SYNC```
```android.permission.POST_NOTIFICATIONS```
```android.permission.WAKE_LOCK```
```android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS```
```android.permission.BLUETOOTH_CONNECT```
```com.huawei.wearengine.permission.DEVICE_MANAGER```

**P2P Communication Setup:**
- ```p2pClient.setPeerPkgName("")``` // Set peer package name
// Set peer fingerprint (For Smart Next Watch(like watch5) use appID, for [lite(like gt/fit/d/ultimate series)/smart wearable(watch4/3)](https://developer.huawei.com/consumer/en/doc/connectivity-guides/signature-0000001053969657))
- ```p2pClient.setPeerFingerPrint("")``` Set peer fingerprint

Documentation Link:
- [Applying for the Wear Engine Service](https://developer.huawei.com/consumer/en/doc/connectivity-Guides/applying-wearengine-0000001050777982)
- [Wear Engine SDK](https://developer.huawei.com/consumer/en/doc/connectivity-Guides/integrating-phone-sdk-0000001051137958)
- [Version Change History](https://developer.huawei.com/consumer/en/doc/connectivity-Guides/version-change-history-0000001086350238)
- [Android Phone App Development](https://developer.huawei.com/consumer/en/doc/connectivity-guides/phone-dev-0000001086797354)

## Key Components
P2pManager
- Sends/receives messages using the Wear Engine P2P client
- Manages peer package name and fingerprint verification
- Registers message receivers for bidirectional communication

DeviceManager
- Discovers and lists bonded Huawei wearable devices
- Retrieves device information (name, capabilities, connection status)
- Used by the app-level auto-binder to pick a connected watch

AuthManager
- Handles `DEVICE_MANAGER` permission requests
- Validates required permissions for wearable access
- Provides permission status callbacks

WatchLinkService
- Foreground service that pins the process at FOREGROUND_SERVICE importance
- Selects its FG type at runtime (`connectedDevice` or `dataSync`)
- Drives the periodic heartbeat send and holds a wake lock

WatchMessenger
- Process-wide holder for the bound device and send/receive logs
- Lets the service reuse the exact Wear Engine clients the app set up

App
- Owns process-wide Wear Engine singletons and a process-scoped coroutine scope
- Installs the app-level auto-binder so binding survives Activity destruction

See [FOREGROUND_SERVICE.md](FOREGROUND_SERVICE.md) for the full design.


# Directory Structure
```
├───java
│   └───com
│       └───hmosdemos
│           └───wearable
│               └───wearengineandroidwatchfilemessagesender
│                   │   App.kt
│                   │   MainActivity.kt
│                   │
│                   ├───managers
│                   │       AuthManager.kt
│                   │       DeviceManager.kt
│                   │       P2pManager.kt
│                   │
│                   ├───service
│                   │       AppLifecycleTracker.kt
│                   │       BatteryOptimization.kt
│                   │       HeartbeatLog.kt
│                   │       WatchLinkService.kt
│                   │       WatchMessenger.kt
│                   │
│                   └───ui
│                       │   WearEngineApp.kt
│                       │
│                       ├───screens
│                       │       ForegroundServiceScreen.kt
│                       │
│                       └───theme
│                               Color.kt
│                               Theme.kt
│                               Type.kt
```
# Constraints and Restrictions

## Supported Devices
- Android Phones
- HMS/GMS Huawei Phones

## Requirements
- [Applying for the Wear Engine Service](https://developer.huawei.com/consumer/en/doc/connectivity-Guides/applying-wearengine-0000001050777982)
- Huawei Health App need to be installed on Phone
- [Wear Engine SDK](https://developer.huawei.com/consumer/en/doc/connectivity-Guides/integrating-phone-sdk-0000001051137958)
- Paired Huawei wearable
- Device Manager permissions
- Configured peer fingerprints (lite wearable or smart wearable)

# LICENSE
[Mobile to Watch] WearEngine Message & File Send/Receiver is distributed under the terms of the MIT License.
See the [license](/LICENSE) for more information.