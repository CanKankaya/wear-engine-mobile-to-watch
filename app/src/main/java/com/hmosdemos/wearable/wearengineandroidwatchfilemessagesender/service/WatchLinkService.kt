package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Long-running foreground service that keeps the app process alive and "promoted"
 * to foreground importance so APIs that require a foreground app (e.g. Huawei
 * Wear Engine on the phone side) keep working while the user is in another app
 * or the screen is off.
 *
 * The service shows an ongoing ("sticky") notification — that notification is
 * what makes the OS treat the process as a foreground service rather than a
 * background one, and it is not dismissable by the user.
 */
class WatchLinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null
    private var watchSenderJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        _startedAtElapsedRealtime.value = SystemClock.elapsedRealtime()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // HwPFWService on Huawei/EMUI looks at the wake-lock tag and force-stops
        // apps whose tag is not in its hardcoded whitelist. Using one of the
        // whitelisted tags ("LocationManagerService") prevents it from killing
        // our service after ~10 min of screen-off + unplugged. See
        // https://dontkillmyapp.com/huawei for background.
        val tag = if (BatteryOptimization.isHuawei()) {
            "LocationManagerService"
        } else {
            "WatchLinkService::sender"
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            // No timeout: we hold it for the lifetime of the FG service so
            // the 5s loop can run with screen off + unplugged. Released in
            // onDestroy().
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Returns true iff this app currently satisfies the runtime prerequisite
     * for declaring [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE] in
     * a `startForeground()` call. On API 31+ the system enforces that the app
     * holds a granted Bluetooth runtime permission; on lower APIs the type is
     * effectively free to use. If the prereq isn't met we must NOT pass the
     * flag or the OS throws ForegroundServiceTypeNotAllowedException.
     */
    private fun hasConnectedDevicePrereq(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val perms = arrayOf(
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
        )
        return perms.any {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        ensureChannel(this)
        val notification = buildNotification(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // dataSync has no runtime prerequisite and is always safe to use.
            // connectedDevice is the more accurate semantic match for an app
            // that holds a Bluetooth link to a watch and may be treated more
            // leniently by some OEMs, but on API 31+ it requires the app to
            // hold a granted runtime Bluetooth permission (BLUETOOTH_CONNECT
            // etc.). We only OR it in when that prereq is met, otherwise the
            // system throws ForegroundServiceTypeNotAllowedException at start.
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (hasConnectedDevicePrereq()) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startHeartbeat()
        startWatchSender()

        return START_STICKY
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                val tick = _heartbeatTick.value + 1
                _heartbeatTick.value = tick
                HeartbeatLog.record(tick)
                delay(1_000)
            }
        }
    }

    /**
     * Every 5 seconds, push a message to the watch via the same P2p path the
     * UI's "Send Message" button uses. No-ops gracefully when no device has
     * been selected yet.
     */
    private fun startWatchSender() {
        watchSenderJob?.cancel()
        watchSenderJob = scope.launch {
            var seq = 0L
            while (true) {
                delay(5_000)
                seq += 1
                // Make sure we have a device before sending. If not, ask the app
                // to find the currently connected watch and bind it.
                if (!WatchMessenger.hasDevice()) {
                    WatchMessenger.tryAutoBind()
                }
                WatchMessenger.send(seq, "FG heartbeat #$seq @${SystemClock.elapsedRealtime()}")
            }
        }
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        watchSenderJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        _isRunning.value = false
        _startedAtElapsedRealtime.value = null
        _heartbeatTick.value = 0
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "watch_link_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP =
            "com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.ACTION_STOP"

        private val _isRunning = MutableStateFlow(false)
        /** True between onCreate() and onDestroy() of the service. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _startedAtElapsedRealtime = MutableStateFlow<Long?>(null)
        val startedAtElapsedRealtime: StateFlow<Long?> = _startedAtElapsedRealtime.asStateFlow()

        private val _heartbeatTick = MutableStateFlow(0L)
        /** Increments every second while the service runs — proof of life for the UI. */
        val heartbeatTick: StateFlow<Long> = _heartbeatTick.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, WatchLinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WatchLinkService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                // IMPORTANCE_DEFAULT (not LOW). LOW-importance FG notifications
                // can be silently restricted by some OEMs (the system treats
                // them like background work). DEFAULT keeps us anchored as a
                // visible, user-facing FG service.
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Watch link",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Keeps the app connected to the watch."
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(context: Context): Notification {
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val stopIntent = PendingIntent.getService(
                context,
                1,
                Intent(context, WatchLinkService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Watch link active")
                .setContentText("Keeping the app in the foreground for the watch.")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // PRIORITY_HIGH + VISIBILITY_PUBLIC are belt-and-suspenders for
                // OEMs that otherwise demote our FG notification (and with it
                // our process priority) when the screen turns off.
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentIntent)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopIntent
                )
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
