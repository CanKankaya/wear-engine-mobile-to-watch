package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide foreground/background tracker.
 *
 * ProcessLifecycleOwner aggregates the lifecycle of every Activity in the
 * process: it goes to STARTED when the first activity is started and back to
 * CREATED (~700ms after) when the last one is stopped. That is the standard
 * way to ask "is the user actually looking at my app right now?".
 *
 * Note: this is independent of the foreground SERVICE state. The service can
 * be running with the app fully backgrounded — that is exactly the point of
 * a foreground service.
 */
object AppLifecycleTracker : DefaultLifecycleObserver {

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        _isAppInForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isAppInForeground.value = false
    }
}
