package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.*
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchLinkService
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.WearEngineApp
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.theme.WearEngineAndroidLiteFileMessageSenderTheme

class MainActivity : ComponentActivity() {
    private lateinit var deviceManager: DeviceManager
    private lateinit var authManager: AuthManager
    private lateinit var p2pManager: P2pManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initializeManagers()
        setupWearEngine()

        setContent {
            WearEngineAndroidLiteFileMessageSenderTheme {
                WearEngineApp()
            }
        }
    }

    private fun initializeManagers() {
        // Reuse the singleton instances from App so the foreground service
        // and the UI share the exact same Wear Engine clients + receiver
        // registrations. This is what lets auto-bind keep working after the
        // Activity is destroyed in the background.
        val app = application as App
        deviceManager = app.deviceManager
        authManager = app.authManager
        p2pManager = app.p2pManager
    }

    private fun setupWearEngine() {
        // Configure the peer package + request the Wear Engine permission so
        // the process-scoped auto-binder (installed in App.onCreate) can find
        // and bind a connected watch for the foreground service.
        p2pManager.setPeerPkgName()
        authManager.checkPermissions(object : AuthManager.AuthCheckCallback {
            override fun onResult(allPermissionsGranted: Boolean) {
                if (!allPermissionsGranted) {
                    authManager.requestPermission(null, null)
                }
            }

            override fun onError(e: Exception?) {
                authManager.requestPermission(null, null)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only tear the receiver down if the foreground service isn't keeping
        // the watch link alive. Otherwise we'd kill the auto-rebind path the
        // moment the user backgrounds the app.
        if (!WatchLinkService.isRunning.value) {
            p2pManager.unregisterReceiver()
        }
    }
}