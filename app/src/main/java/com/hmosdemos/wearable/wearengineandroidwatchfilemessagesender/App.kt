package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender

import android.app.Application
import android.util.Log
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.AuthManager
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.DeviceManager
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.P2pManager
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.AppLifecycleTracker
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchMessenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    /**
     * Process-wide singletons. Kept on [App] so the foreground service can
     * reuse the exact same Wear Engine clients the [MainActivity] uses, even
     * after the Activity is destroyed (e.g. user backgrounds the app and
     * Android frees the Activity). Lazy so they only initialize on first use.
     */
    val deviceManager: DeviceManager by lazy { DeviceManager(this) }
    val p2pManager: P2pManager by lazy { P2pManager(this) }
    val authManager: AuthManager by lazy { AuthManager(this) }

    /**
     * Survives the entire process lifetime — unlike `viewModelScope`, which
     * dies when the Activity is destroyed. The foreground service's
     * dies when the Activity is destroyed. The foreground service's
     * auto-bind work runs here so it keeps working when the app is in the
     * background and the Activity has been freed.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLifecycleTracker.install()
        installAppLevelAutoBinder()
    }

    /**
     * Wires [WatchMessenger.tryAutoBind] to a callback that uses our own
     * process-scoped coroutine + the singleton managers above. That way the
     * service can rebind to a connected watch even when no Activity /
     * ViewModel exists.
     */
    private fun installAppLevelAutoBinder() {
        WatchMessenger.setAutoBinder { onResult ->
            appScope.launch {
                deviceManager.getBondedDevices(
                    { deviceList ->
                        val devices = deviceList?.filterNotNull().orEmpty()
                        if (devices.isEmpty()) {
                            onResult(false, "No bonded devices")
                            return@getBondedDevices
                        }
                        val connected = devices.firstOrNull {
                            runCatching { it.isConnected }.getOrDefault(false)
                        }
                        if (connected == null) {
                            onResult(false, "No connected device (${devices.size} bonded)")
                            return@getBondedDevices
                        }
                        // Re-register the receiver on the singleton P2pManager
                        // so messages from the watch are surfaced even when no
                        // Activity is alive. The receiver records into
                        // WatchMessenger so the FG screen still sees them.
                        p2pManager.registerReceiver(connected, object : P2pManager.MessageListener {
                            override fun onMessageReceived(message: String?) {
                                message?.let { WatchMessenger.recordReceived(it) }
                            }
                            override fun onMessageSent(message: String?) {}
                        })
                        WatchMessenger.bind(p2pManager, connected)
                        onResult(true, "Bound to ${connected.name}")
                    },
                    { error ->
                        Log.w("App", "auto-bind getBondedDevices failed", error)
                        onResult(false, error.message ?: "Device load failed")
                    }
                )
            }
        }
    }

    companion object {
        @Volatile
        private var instance: App? = null
        fun get(): App = instance
            ?: error("App.get() called before Application.onCreate()")
    }
}
