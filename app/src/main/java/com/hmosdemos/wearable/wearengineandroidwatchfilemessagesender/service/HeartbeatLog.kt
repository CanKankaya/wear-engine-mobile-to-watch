package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service

import android.app.ActivityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ring-buffer log of one snapshot per heartbeat tick.
 *
 * The service appends an entry every second while it is running, capturing
 * what the app's visibility / process importance was at that moment. The UI
 * just reads the flow.
 */
object HeartbeatLog {

    data class Entry(
        val tick: Long,
        val appInForeground: Boolean,
        val importance: Int,
    )

    private const val MAX_ENTRIES = 300

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun record(tick: Long) {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        val entry = Entry(
            tick = tick,
            appInForeground = AppLifecycleTracker.isAppInForeground.value,
            importance = info.importance,
        )
        val current = _entries.value
        // Newest first, capped.
        val next = ArrayList<Entry>(minOf(current.size + 1, MAX_ENTRIES))
        next.add(entry)
        for (i in 0 until minOf(current.size, MAX_ENTRIES - 1)) {
            next.add(current[i])
        }
        _entries.value = next
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
