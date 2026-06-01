package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.AppLifecycleTracker
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.BatteryOptimization
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.HeartbeatLog
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchLinkService
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchMessenger

@Composable
fun ForegroundServiceScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val serviceRunning by WatchLinkService.isRunning.collectAsStateWithLifecycle()
    val heartbeat by WatchLinkService.heartbeatTick.collectAsStateWithLifecycle()
    val startedAt by WatchLinkService.startedAtElapsedRealtime.collectAsStateWithLifecycle()
    val appInForeground by AppLifecycleTracker.isAppInForeground.collectAsStateWithLifecycle()
    val log by HeartbeatLog.entries.collectAsStateWithLifecycle()
    val watchSentCount by WatchMessenger.sentCount.collectAsStateWithLifecycle()
    val watchFailedCount by WatchMessenger.failedCount.collectAsStateWithLifecycle()
    val watchLastEntry by WatchMessenger.lastEntry.collectAsStateWithLifecycle()
    val watchSendEntries by WatchMessenger.entries.collectAsStateWithLifecycle()
    val watchReceivedCount by WatchMessenger.receivedCount.collectAsStateWithLifecycle()
    val watchLastReceived by WatchMessenger.lastReceived.collectAsStateWithLifecycle()
    val watchReceivedEntries by WatchMessenger.receivedEntries.collectAsStateWithLifecycle()
    val autoBindStatus by WatchMessenger.autoBindStatus.collectAsStateWithLifecycle()

    // Re-evaluated every frame so the "now" clock stays live.
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { nowElapsed = SystemClock.elapsedRealtime() }
        }
    }

    var notificationsAllowed by remember { mutableStateOf(hasNotificationPermission(context)) }

    // Android only shows one permission dialog at a time — if we fire two
    // launch() calls back-to-back the second one is silently dropped. So we
    // chain them: BLUETOOTH_CONNECT → POST_NOTIFICATIONS → start service.
    // The launcher callbacks all check `pendingStartAfterPermissions` and
    // either advance to the next step or finally start the FG service.
    var pendingStartAfterPermissions by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
        if (pendingStartAfterPermissions && granted) {
            pendingStartAfterPermissions = false
            WatchLinkService.start(context)
        } else {
            pendingStartAfterPermissions = false
        }
    }

    // BLUETOOTH_CONNECT (API 31+) satisfies the runtime prerequisite for
    // declaring the connectedDevice foreground-service type. The service
    // works without it (it falls back to dataSync), but having it grants us
    // the more accurate FG type and slightly more lenient OEM treatment.
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Regardless of grant/deny, move on to the notification prompt.
        if (pendingStartAfterPermissions) {
            if (hasNotificationPermission(context)) {
                pendingStartAfterPermissions = false
                WatchLinkService.start(context)
            } else {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Re-check whether the OS still considers us battery-optimized whenever
    // the user comes back from the system dialog.
    var batteryWhitelisted by remember {
        mutableStateOf(BatteryOptimization.isIgnoringBatteryOptimizations(context))
    }
    LaunchedEffect(appInForeground) {
        if (appInForeground) {
            batteryWhitelisted = BatteryOptimization.isIgnoringBatteryOptimizations(context)
        }
    }
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryWhitelisted = BatteryOptimization.isIgnoringBatteryOptimizations(context)
    }
    val hasOemManager = remember {
        BatteryOptimization.oemPowerManagerIntent(context) != null
    }

    // Helper: tries every known OEM "app launch" / autostart intent in
    // order and falls back to this app's settings page. Toasts the result
    // so the user knows where they landed (or why it failed).
    fun openOemPowerManager() {
        val label = BatteryOptimization.openOemPowerManager(context)
        if (label == null) {
            Toast.makeText(
                context,
                "Couldn't open any power manager screen on this device.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                context,
                "Opened: $label",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    var useFromWatch by remember { mutableStateOf(serviceRunning) }
    // Keep the toggle in sync if the user stops the service from the notification.
    LaunchedEffect(serviceRunning) { useFromWatch = serviceRunning }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Foreground Service",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Turn the switch on to keep the Wear Engine connection alive " +
                "in the background. A sticky notification will be shown.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use from watch",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (useFromWatch)
                            "Service running — phone stays \"foreground\" for the watch."
                        else
                            "Off — phone behaves like a normal app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = useFromWatch,
                    onCheckedChange = { wantOn ->
                        useFromWatch = wantOn
                        if (wantOn) {
                            // Try to bind a connected watch right away so the
                            // first 5s tick already has a device. The bind
                            // work itself lives in App's process-scoped
                            // auto-binder so it keeps working in the background.
                            if (!WatchMessenger.hasDevice()) {
                                WatchMessenger.tryAutoBind()
                            }
                            // Chain the permission prompts so the user only
                            // has to toggle the switch once:
                            //   BLUETOOTH_CONNECT (if needed) → POST_NOTIFICATIONS
                            //   (if needed) → start service.
                            pendingStartAfterPermissions = true
                            val needsBluetooth =
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    ) != PackageManager.PERMISSION_GRANTED
                            when {
                                needsBluetooth -> {
                                    bluetoothPermissionLauncher.launch(
                                        Manifest.permission.BLUETOOTH_CONNECT
                                    )
                                }
                                !hasNotificationPermission(context) -> {
                                    permissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                                else -> {
                                    pendingStartAfterPermissions = false
                                    WatchLinkService.start(context)
                                }
                            }
                        } else {
                            WatchLinkService.stop(context)
                        }
                    }
                )
            }
        }

        StatusCard(
            title = "App (Activity / process)",
            rows = listOf(
                "UI visible to user" to appInForeground.toString(),
                "Process importance" to processImportanceLabel(),
            )
        )

        BatteryWhitelistCard(
            whitelisted = batteryWhitelisted,
            hasOemManager = hasOemManager,
            onRequest = {
                // Diagnose silent failures: tell the user exactly what's
                // happening when they tap the button, so we can tell apart
                // "system never shows dialog" from "already whitelisted" from
                // "OEM blocked the intent".
                if (BatteryOptimization.isIgnoringBatteryOptimizations(context)) {
                    Toast.makeText(
                        context,
                        "Already whitelisted from battery optimization.",
                        Toast.LENGTH_SHORT
                    ).show()
                    batteryWhitelisted = true
                    return@BatteryWhitelistCard
                }
                if (!BatteryOptimization.canRequestIgnoreOptimizations(context)) {
                    // The direct request intent isn't supported on this build.
                    // Open the settings list as a fallback so the user can at
                    // least find us.
                    Toast.makeText(
                        context,
                        "Direct prompt not supported here \u2014 opening settings list.",
                        Toast.LENGTH_LONG
                    ).show()
                    runCatching { context.startActivity(BatteryOptimization.settingsListIntent()) }
                        .onFailure {
                            Toast.makeText(
                                context,
                                "Couldn't open battery settings: ${it.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    return@BatteryWhitelistCard
                }
                val launched = runCatching {
                    batteryOptLauncher.launch(BatteryOptimization.requestIgnoreIntent(context))
                }
                launched.onFailure { err ->
                    Toast.makeText(
                        context,
                        "Prompt failed (${err.javaClass.simpleName}) \u2014 opening settings list.",
                        Toast.LENGTH_LONG
                    ).show()
                    runCatching { context.startActivity(BatteryOptimization.settingsListIntent()) }
                }
            },
            onOpenOemManager = { openOemPowerManager() }
        )

        OemPowerManagerCard(
            onOpenOemManager = { openOemPowerManager() }
        )

        StatusCard(
            title = "Foreground service",
            rows = listOf(
                "Service alive" to serviceRunning.toString(),
                "Sticky notification posted" to isOurNotificationActive(context).toString(),
                "Notification permission" to notificationsAllowed.toString(),
                "Heartbeat tick" to heartbeat.toString(),
                "Uptime" to formatUptime(startedAt, nowElapsed),
            )
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Watch binding (auto)",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (WatchMessenger.hasDevice())
                        "A device is currently bound. Sending + receiving are active."
                    else
                        "No device bound. The service will retry every 5s while running.",
                    style = MaterialTheme.typography.bodySmall
                )
                autoBindStatus?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Last attempt: $it",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }

        StatusCard(
            title = "Watch receiver",
            rows = listOf(
                "Device bound" to WatchMessenger.hasDevice().toString(),
                "Messages received" to watchReceivedCount.toString(),
                "Last text" to (watchLastReceived?.text ?: "—"),
                "Last at" to (watchLastReceived?.let {
                    WatchMessenger.formatTime(it.timestampMs)
                } ?: "—"),
            )
        )

        WatchReceiveLogCard(
            entries = watchReceivedEntries,
            onClear = { WatchMessenger.clearReceivedLog() }
        )

        StatusCard(
            title = "Watch sender (every 5s)",
            rows = listOf(
                "Device bound" to WatchMessenger.hasDevice().toString(),
                "Sent OK" to watchSentCount.toString(),
                "Failed" to watchFailedCount.toString(),
                "Last status" to (watchLastEntry?.let {
                    "${it.status.name}${it.detail?.let { d -> " ($d)" } ?: ""}"
                } ?: "—"),
                "Last text" to (watchLastEntry?.text ?: "—"),
                "Last at" to (watchLastEntry?.let { WatchMessenger.formatTime(it.timestampMs) } ?: "—"),
            )
        )

        WatchSendLogCard(entries = watchSendEntries, onClear = { WatchMessenger.clearLog() })

        HeartbeatLogCard(entries = log, onClear = { HeartbeatLog.clear() })

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "How to test",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "• Turn it on, grant notifications.\n" +
                        "• Press Home — service keeps ticking.\n" +
                        "• Swipe from Recents — still ticks.\n" +
                        "• Tap Stop on the notification to end it.\n" +
                        "• Lock the screen unplugged — should still tick.\n" +
                        "  If it stops, tap \"Disable battery optimization\"\n" +
                        "  above (and on Huawei, also enable us in Phone\n" +
                        "  Manager → Battery → App launch → Manage manually).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BatteryWhitelistCard(
    whitelisted: Boolean,
    hasOemManager: Boolean,
    onRequest: () -> Unit,
    onOpenOemManager: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (whitelisted) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Battery optimization",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (whitelisted)
                    "Whitelisted — Doze + App Standby will NOT throttle the " +
                        "sender while the screen is off and unplugged."
                else
                    "Not whitelisted. With screen off + unplugged, Android " +
                        "Doze can suspend our network access and the watch " +
                        "will stop receiving until you plug in the charger. " +
                        "Tap below to allow.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!whitelisted) {
                    Button(onClick = onRequest) {
                        Text("Disable battery optimization")
                    }
                }
                if (hasOemManager) {
                    TextButton(onClick = onOpenOemManager) {
                        Text("Open OEM power manager")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeartbeatLogCard(
    entries: List<HeartbeatLog.Entry>,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Heartbeat history",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${entries.size} entries",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Text(
                        text = "No ticks yet. Turn the service on.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LogHeaderCell("#", weight = 0.8f)
                        LogHeaderCell("App", weight = 1.2f)
                        LogHeaderCell("Importance", weight = 2f)
                        LogHeaderCell("No.", weight = 1f)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(entries) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                LogCell(entry.tick.toString(), weight = 0.8f)
                                LogCell(
                                    if (entry.appInForeground) "FG" else "BG",
                                    weight = 1.2f
                                )
                                LogCell(shortImportance(entry.importance), weight = 2f)
                                LogCell(entry.importance.toString(), weight = 1f)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LogHeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    )
}

@Composable
private fun RowScope.LogCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    )
}

private fun shortImportance(value: Int): String = when (value) {
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FG_SERVICE"
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
    android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
    else -> value.toString()
}

@Composable
private fun WatchSendLogCard(
    entries: List<WatchMessenger.Entry>,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Watch send log",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${entries.size} entries",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Text(
                        text = "No sends yet. Select a device on the first screen, " +
                            "then turn the service on.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(entries.asReversed()) { entry ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "[${WatchMessenger.formatTime(entry.timestampMs)}] " +
                                            "#${entry.seq}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                    Text(
                                        text = entry.status.name,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = statusColor(entry.status)
                                        )
                                    )
                                }
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                                entry.detail?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.error,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: WatchMessenger.Status) = when (status) {
    WatchMessenger.Status.SENT -> MaterialTheme.colorScheme.primary
    WatchMessenger.Status.SENDING -> MaterialTheme.colorScheme.tertiary
    WatchMessenger.Status.FAILED -> MaterialTheme.colorScheme.error
    WatchMessenger.Status.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun WatchReceiveLogCard(
    entries: List<WatchMessenger.ReceivedEntry>,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Watch receive log",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${entries.size} entries",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Text(
                        text = "No messages received yet. Bind a connected watch above.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(entries.asReversed()) { entry ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "[${WatchMessenger.formatTime(entry.timestampMs)}] " +
                                        "#${entry.seq}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, rows: List<Pair<String, String>>) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun OemPowerManagerCard(onOpenOemManager: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "OEM power management",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "On Huawei / Honor / Oppo / Vivo, the standard battery " +
                    "whitelist isn't enough — the vendor's own app-launch " +
                    "manager will still kill this app. You must also allow it " +
                    "there:",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Settings → Battery → App launch → this app:\n" +
                    "  • turn OFF \u201CManage automatically\u201D\n" +
                    "  • turn ON all three toggles",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                )
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Samsung / Pixel / most others: not needed — the " +
                    "battery whitelist above is enough.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenOemManager) {
                Text("Open power management settings")
            }
        }
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun isOurNotificationActive(context: Context): Boolean {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    return nm.activeNotifications.any { it.id == WatchLinkService.NOTIFICATION_ID }
}

private fun processImportanceLabel(): String {
    val info = android.app.ActivityManager.RunningAppProcessInfo()
    android.app.ActivityManager.getMyMemoryState(info)
    return when (info.importance) {
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
            "FOREGROUND (100)"
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
            "FOREGROUND_SERVICE (125)"
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING ->
            "TOP_SLEEPING (150)"
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
            "VISIBLE (200)"
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE ->
            "PERCEPTIBLE (230)"
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ->
            "SERVICE (300)"
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED ->
            "CACHED (400)"
        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE ->
            "GONE (1000)"
        else -> "${info.importance}"
    }
}

private fun formatUptime(startedAt: Long?, now: Long): String {
    if (startedAt == null) return "—"
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
