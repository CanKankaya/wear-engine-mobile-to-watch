package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
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
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.HeartbeatLog
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchLinkService
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchMessenger
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.viewmodels.MainViewModel

@Composable
fun ForegroundServiceScreen(
    viewModel: MainViewModel,
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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted
        if (granted) WatchLinkService.start(context)
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
                            // first 5s tick already has a device.
                            if (!WatchMessenger.hasDevice()) {
                                viewModel.autoBindConnectedDeviceForService { _, _ -> }
                            }
                            if (hasNotificationPermission(context)) {
                                WatchLinkService.start(context)
                            } else {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                        "• Tap Stop on the notification to end it.",
                    style = MaterialTheme.typography.bodySmall
                )
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
