package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.*
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchLinkService
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.WearEngineApp
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.theme.WearEngineAndroidLiteFileMessageSenderTheme
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.viewmodels.MainViewModel
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.viewmodels.MainViewModelFactory

class MainActivity : ComponentActivity() {
    private lateinit var deviceManager: DeviceManager
    private lateinit var authManager: AuthManager
    private lateinit var p2pManager: P2pManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initializeManagers()

        setContent {
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModelFactory(
                    authManager = authManager,
                    deviceManager = deviceManager,
                    p2pManager = p2pManager
                )
            )

            WearEngineAndroidLiteFileMessageSenderTheme {
                WearEngineApp(viewModel = viewModel)
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