package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender

import android.app.Application
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.AppLifecycleTracker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLifecycleTracker.install()
    }
}
