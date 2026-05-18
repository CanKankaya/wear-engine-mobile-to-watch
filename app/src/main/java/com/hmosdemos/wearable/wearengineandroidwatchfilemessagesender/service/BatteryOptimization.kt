package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers around Android's battery optimization (Doze + App Standby) whitelist.
 *
 * Why we need this: a foreground service prevents our process from being killed,
 * but it does NOT prevent Doze from suspending CPU + network when the screen is
 * off and the device is unplugged. The result on Android 10 is that our 5s
 * Wear Engine sender stops dispatching to the watch until the user plugs in a
 * charger (which exits Doze).
 *
 * The documented fix for "peripheral device companion app" use cases is to ask
 * the user to whitelist us via [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
 */
object BatteryOptimization {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Pops the system dialog "Allow [app] to ignore battery optimizations?".
     * If the user accepts, our app is exempted from Doze + App Standby. This
     * is the only programmatic way to keep network/CPU running with screen
     * off + unplugged on stock Android 10+.
     *
     * NOTE: Do NOT set FLAG_ACTIVITY_NEW_TASK here. This intent is launched
     * via [androidx.activity.result.ActivityResultLauncher] which already
     * starts it in the correct task; the NEW_TASK flag causes some OEMs
     * (including Huawei EMUI) to silently no-op the launch, with no dialog
     * shown to the user.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * True iff some activity on this device claims to handle the
     * battery-optimization request intent. Some heavily customized Android
     * builds (and some emulators) don't, in which case we must fall back to
     * [settingsListIntent].
     */
    fun canRequestIgnoreOptimizations(context: Context): Boolean {
        return requestIgnoreIntent(context).resolveActivity(context.packageManager) != null
    }

    /**
     * Opens the system battery-optimization settings list as a fallback (e.g.
     * if the device blocks the direct request intent).
     */
    fun settingsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Best-effort intent to open the OEM "protected apps" / autostart manager.
     * Huawei (this app's main target since it uses Wear Engine) ships an extra
     * power manager on top of stock Android that overrides the standard
     * battery-optimization whitelist. The user must enable us there too,
     * otherwise the OS will still suspend us with screen off + unplugged.
     *
     * Returns null if no known OEM manager is detected.
     */
    fun oemPowerManagerIntent(context: Context): Intent? {
        val candidates = listOf(
            // Huawei
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            // Xiaomi
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // Oppo
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            // Vivo
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            // OnePlus
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        val pm = context.packageManager
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(pm) != null) return intent
        }
        return null
    }

    /**
     * True on API levels where Doze actually exists and matters for us.
     * Doze landed in API 23 (M).
     */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /**
     * True if running on a Huawei / Honor device. These devices ship
     * additional, non-AOSP power management (PowerGenie, HwPFWService,
     * App Launch manager) that the standard battery whitelist does NOT
     * disable, so we need to surface extra instructions to the user.
     */
    fun isHuawei(): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val b = Build.BRAND.orEmpty().lowercase()
        return m.contains("huawei") || m.contains("honor") ||
            b.contains("huawei") || b.contains("honor")
    }
}
