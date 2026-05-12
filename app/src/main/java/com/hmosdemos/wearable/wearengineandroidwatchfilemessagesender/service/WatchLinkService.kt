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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        _startedAtElapsedRealtime.value = SystemClock.elapsedRealtime()
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
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
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
                WatchMessenger.send(seq, "FG heartbeat #$seq @${SystemClock.elapsedRealtime()}")
            }
        }
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        watchSenderJob?.cancel()
        scope.cancel()
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
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Watch link",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the app connected to the watch."
                    setShowBadge(false)
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
